/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapreduce.lib.output;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.hadoop.util.concurrent.HadoopExecutors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapFile;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.task.JobContextImpl;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@SuppressWarnings("unchecked")
public class TestFileOutputCommitter {
  private static final Path outDir = new Path(
      System.getProperty("test.build.data",
          System.getProperty("java.io.tmpdir")),
      TestFileOutputCommitter.class.getName());

  private final static String SUB_DIR = "SUB_DIR";
  private final static Path OUT_SUB_DIR = new Path(outDir, SUB_DIR);

  private static final Logger LOG =
      LoggerFactory.getLogger(TestFileOutputCommitter.class);

  // A random task attempt id for testing.
  private static final String attempt = "attempt_200707121733_0001_m_000000_0";
  private static final String partFile = "part-m-00000";
  private static final TaskAttemptID taskID = TaskAttemptID.forName(attempt);

  private static final String attempt1 = "attempt_200707121733_0001_m_000001_0";
  private static final TaskAttemptID taskID1 = TaskAttemptID.forName(attempt1);

  private Text key1 = new Text("key1");
  private Text key2 = new Text("key2");
  private Text val1 = new Text("val1");
  private Text val2 = new Text("val2");

  
  private static void cleanup() throws IOException {
    Configuration conf = new Configuration();
    FileSystem fs = outDir.getFileSystem(conf);
    fs.delete(outDir, true);
  }
  
  @BeforeEach
  public void setUp() throws IOException {
    cleanup();
  }

  @AfterEach
  public void tearDown() throws IOException {
    cleanup();
  }
  
  private void writeOutput(RecordWriter theRecordWriter,
      TaskAttemptContext context) throws IOException, InterruptedException {
    NullWritable nullWritable = NullWritable.get();

    try {
      theRecordWriter.write(key1, val1);
      theRecordWriter.write(null, nullWritable);
      theRecordWriter.write(null, val1);
      theRecordWriter.write(nullWritable, val2);
      theRecordWriter.write(key2, nullWritable);
      theRecordWriter.write(key1, null);
      theRecordWriter.write(null, null);
      theRecordWriter.write(key2, val2);
    } finally {
      theRecordWriter.close(context);
    }
  }

  private void writeMapFileOutput(RecordWriter theRecordWriter,
      TaskAttemptContext context) throws IOException, InterruptedException {
    try {
      int key = 0;
      for (int i = 0 ; i < 10; ++i) {
        key = i;
        Text val = (i%2 == 1) ? val1 : val2;
        theRecordWriter.write(new LongWritable(key),
            val);        
      }
    } finally {
      theRecordWriter.close(context);
    }
  }
  
  private void testRecoveryInternal(int commitVersion, int recoveryVersion)
      throws Exception {
    Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(MRJobConfig.APPLICATION_ATTEMPT_ID, 1);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        commitVersion);
    JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
    TaskAttemptContext tContext = new TaskAttemptContextImpl(conf, taskID);
    FileOutputCommitter committer = new FileOutputCommitter(outDir, tContext);

    // setup
    committer.setupJob(jContext);
    committer.setupTask(tContext);

    // write output
    TextOutputFormat theOutputFormat = new TextOutputFormat();
    RecordWriter theRecordWriter = theOutputFormat.getRecordWriter(tContext);
    writeOutput(theRecordWriter, tContext);

    // do commit
    committer.commitTask(tContext);

    Path jobTempDir1 = committer.getCommittedTaskPath(tContext);
    File jtd = new File(jobTempDir1.toUri().getPath());
    if (commitVersion == 1) {
      assertTrue(jtd.exists(), "Version 1 commits to temporary dir " + jtd);
      validateContent(jtd);
    } else {
      assertFalse(jtd.exists(), "Version 2 commits to output dir " + jtd);
    }

    //now while running the second app attempt, 
    //recover the task output from first attempt
    Configuration conf2 = job.getConfiguration();
    conf2.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf2.setInt(MRJobConfig.APPLICATION_ATTEMPT_ID, 2);
    conf2.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        recoveryVersion);
    JobContext jContext2 = new JobContextImpl(conf2, taskID.getJobID());
    TaskAttemptContext tContext2 = new TaskAttemptContextImpl(conf2, taskID);
    FileOutputCommitter committer2 = new FileOutputCommitter(outDir, tContext2);
    committer2.setupJob(tContext2);
    Path jobTempDir2 = committer2.getCommittedTaskPath(tContext2);
    File jtd2 = new File(jobTempDir2.toUri().getPath());

    committer2.recoverTask(tContext2);
    if (recoveryVersion == 1) {
      assertTrue(jtd2.exists(), "Version 1 recovers to " + jtd2);
      validateContent(jtd2);
    } else {
      assertFalse(jtd2.exists(), "Version 2 commits to output dir " + jtd2);
      if (commitVersion == 1) {
        assertEquals(0, jtd.list().length, "Version 2  recovery moves to output dir from " + jtd);
      }
    }

    committer2.commitJob(jContext2);
    validateContent(outDir);
    FileUtil.fullyDelete(new File(outDir.toString()));
  }

  @Test
  public void testRecoveryV1() throws Exception {
    testRecoveryInternal(1, 1);
  }

  @Test
  public void testRecoveryV2() throws Exception {
    testRecoveryInternal(2, 2);
  }

  @Test
  public void testRecoveryUpgradeV1V2() throws Exception {
    testRecoveryInternal(1, 2);
  }

  private void validateContent(Path dir) throws IOException {
    validateContent(new File(dir.toUri().getPath()));
  }
  
  private void validateContent(File dir) throws IOException {
    File expectedFile = new File(dir, partFile);
    assertTrue(expectedFile.exists(), "Could not find "+expectedFile);
    StringBuilder expectedOutput = new StringBuilder();
    expectedOutput.append(key1).append('\t').append(val1).append("\n");
    expectedOutput.append(val1).append("\n");
    expectedOutput.append(val2).append("\n");
    expectedOutput.append(key2).append("\n");
    expectedOutput.append(key1).append("\n");
    expectedOutput.append(key2).append('\t').append(val2).append("\n");
    String output = slurp(expectedFile);
    assertThat(output).isEqualTo(expectedOutput.toString());
  }

  private void validateMapFileOutputContent(
      FileSystem fs, Path dir) throws IOException {
    // map output is a directory with index and data files
    Path expectedMapDir = new Path(dir, partFile);
    assert(fs.getFileStatus(expectedMapDir).isDirectory());    
    FileStatus[] files = fs.listStatus(expectedMapDir);
    int fileCount = 0;
    boolean dataFileFound = false; 
    boolean indexFileFound = false; 
    for (FileStatus f : files) {
      if (f.isFile()) {
        ++fileCount;
        if (f.getPath().getName().equals(MapFile.INDEX_FILE_NAME)) {
          indexFileFound = true;
        }
        else if (f.getPath().getName().equals(MapFile.DATA_FILE_NAME)) {
          dataFileFound = true;
        }
      }
    }
    assert(fileCount > 0);
    assert(dataFileFound && indexFileFound);
  }

  private void testCommitterInternal(int version, boolean taskCleanup)
      throws Exception {
    Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(
        FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        version);
    conf.setBoolean(
        FileOutputCommitter.FILEOUTPUTCOMMITTER_TASK_CLEANUP_ENABLED,
        taskCleanup);
    JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
    TaskAttemptContext tContext = new TaskAttemptContextImpl(conf, taskID);
    FileOutputCommitter committer = new FileOutputCommitter(outDir, tContext);

    // setup
    committer.setupJob(jContext);
    committer.setupTask(tContext);

    // write output
    TextOutputFormat theOutputFormat = new TextOutputFormat();
    RecordWriter theRecordWriter = theOutputFormat.getRecordWriter(tContext);
    writeOutput(theRecordWriter, tContext);

    // check task and job temp directories exist
    File jobOutputDir = new File(
        new Path(outDir, FileOutputCommitter.PENDING_DIR_NAME).toString());
    File taskOutputDir = new File(Path.getPathWithoutSchemeAndAuthority(
        committer.getWorkPath()).toString());
    assertTrue(jobOutputDir.exists(), "job temp dir does not exist");
    assertTrue(taskOutputDir.exists(), "task temp dir does not exist");

    // do commit
    committer.commitTask(tContext);
    assertTrue(jobOutputDir.exists(), "job temp dir does not exist");
    if (version == 1 || taskCleanup) {
      // Task temp dir gets renamed in v1 and deleted if taskCleanup is
      // enabled in v2
      assertFalse(taskOutputDir.exists(), "task temp dir still exists");
    } else {
      // By default, in v2 the task temp dir is only deleted during commitJob
      assertTrue(taskOutputDir.exists(), "task temp dir does not exist");
    }

    // Entire job temp directory gets deleted, including task temp dir
    committer.commitJob(jContext);
    assertFalse(jobOutputDir.exists(), "job temp dir still exists");
    assertFalse(taskOutputDir.exists(), "task temp dir still exists");

    // validate output
    validateContent(outDir);
    FileUtil.fullyDelete(new File(outDir.toString()));
  }

  @Test
  public void testCommitterV1() throws Exception {
    testCommitterInternal(1, false);
  }

  @Test
  public void testCommitterV2() throws Exception {
    testCommitterInternal(2, false);
  }

  @Test
  public void testCommitterV2TaskCleanupEnabled() throws Exception {
    testCommitterInternal(2, true);
  }

  @Test
  public void testCommitterWithDuplicatedCommitV1() throws Exception {
    testCommitterWithDuplicatedCommitInternal(1);
  }

  @Test
  public void testCommitterWithDuplicatedCommitV2() throws Exception {
    testCommitterWithDuplicatedCommitInternal(2);
  }

  private void testCommitterWithDuplicatedCommitInternal(int version) throws
      Exception {
    Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        version);
    JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
    TaskAttemptContext tContext = new TaskAttemptContextImpl(conf, taskID);
    FileOutputCommitter committer = new FileOutputCommitter(outDir, tContext);

    // setup
    committer.setupJob(jContext);
    committer.setupTask(tContext);

    // write output
    TextOutputFormat theOutputFormat = new TextOutputFormat();
    RecordWriter theRecordWriter = theOutputFormat.getRecordWriter(tContext);
    writeOutput(theRecordWriter, tContext);

    // do commit
    committer.commitTask(tContext);
    committer.commitJob(jContext);

    // validate output
    validateContent(outDir);

    // commit job again on a successful commit job.
    try {
      committer.commitJob(jContext);
      if (version == 1) {
        fail("Duplicate commit success: wrong behavior for version 1.");
      }
    } catch (IOException e) {
      if (version == 2) {
        fail("Duplicate commit failed: wrong behavior for version 2.");
      }
    }
    FileUtil.fullyDelete(new File(outDir.toString()));
  }

  @Test
  public void testCommitterWithFailureV1() throws Exception {
    testCommitterWithFailureInternal(1, 1);
    testCommitterWithFailureInternal(1, 2);
  }

  @Test
  public void testCommitterWithFailureV2() throws Exception {
    testCommitterWithFailureInternal(2, 1);
    testCommitterWithFailureInternal(2, 2);
  }

  private void testCommitterWithFailureInternal(int version, int maxAttempts)
      throws Exception {
    Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        version);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_FAILURE_ATTEMPTS,
        maxAttempts);

    JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
    TaskAttemptContext tContext = new TaskAttemptContextImpl(conf, taskID);
    FileOutputCommitter committer = new CommitterWithFailedThenSucceed(outDir,
        tContext);

    // setup
    committer.setupJob(jContext);
    committer.setupTask(tContext);

    // write output
    TextOutputFormat theOutputFormat = new TextOutputFormat();
    RecordWriter theRecordWriter = theOutputFormat.getRecordWriter(tContext);
    writeOutput(theRecordWriter, tContext);

    // do commit
    committer.commitTask(tContext);

    try {
      committer.commitJob(jContext);
      // (1,1), (1,2), (2,1) shouldn't reach to here.
      if (version == 1 || maxAttempts <= 1) {
        fail("Commit successful: wrong behavior for version 1.");
      }
    } catch (IOException e) {
      // (2,2) shouldn't reach to here.
      if (version == 2 && maxAttempts > 2) {
        fail("Commit failed: wrong behavior for version 2.");
      }
    }

    FileUtil.fullyDelete(new File(outDir.toString()));
  }

  @Test
  public void testProgressDuringMerge() throws Exception {
    Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        2);
    JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
    TaskAttemptContext tContext = spy(new TaskAttemptContextImpl(conf, taskID));
    FileOutputCommitter committer = new FileOutputCommitter(outDir, tContext);

    // setup
    committer.setupJob(jContext);
    committer.setupTask(tContext);

    // write output
    MapFileOutputFormat theOutputFormat = new MapFileOutputFormat();
    RecordWriter theRecordWriter = theOutputFormat.getRecordWriter(tContext);
    writeMapFileOutput(theRecordWriter, tContext);

    // do commit
    committer.commitTask(tContext);
    //make sure progress flag was set.
    // The first time it is set is during commit but ensure that
    // mergePaths call makes it go again.
    verify(tContext, atLeast(2)).progress();
  }

  @Test
  public void testCommitterRepeatableV1() throws Exception {
    testCommitterRetryInternal(1);
  }

  @Test
  public void testCommitterRepeatableV2() throws Exception {
    testCommitterRetryInternal(2);
  }

  // retry committer for 2 times.
  private void testCommitterRetryInternal(int version)
      throws Exception {
    Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        version);
    // only attempt for 1 time.
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_FAILURE_ATTEMPTS,
        1);

    JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
    TaskAttemptContext tContext = new TaskAttemptContextImpl(conf, taskID);
    FileOutputCommitter committer = new CommitterWithFailedThenSucceed(outDir,
        tContext);

    // setup
    committer.setupJob(jContext);
    committer.setupTask(tContext);

    // write output
    TextOutputFormat theOutputFormat = new TextOutputFormat();
    RecordWriter theRecordWriter = theOutputFormat.getRecordWriter(tContext);
    writeOutput(theRecordWriter, tContext);

    // do commit
    committer.commitTask(tContext);

    try {
      committer.commitJob(jContext);
      fail("Commit successful: wrong behavior for the first time " +
          "commit.");
    } catch (IOException e) {
      // commit again.
      try {
        committer.commitJob(jContext);
        // version 1 shouldn't reach to here.
        if (version == 1) {
          fail("Commit successful after retry: wrong behavior for " +
              "version 1.");
        }
      } catch (FileNotFoundException ex) {
        if (version == 2) {
          fail("Commit failed after retry: wrong behavior for" +
              " version 2.");
        }
        assertTrue(ex.getMessage().contains(committer.getJobAttemptPath(
            jContext).toString() + " does not exist"));
      }
    }

    FileUtil.fullyDelete(new File(outDir.toString()));
  }

  private void testMapFileOutputCommitterInternal(int version)
      throws Exception {
    Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        version);
    JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
    TaskAttemptContext tContext = new TaskAttemptContextImpl(conf, taskID);
    FileOutputCommitter committer = new FileOutputCommitter(outDir, tContext);

    // setup
    committer.setupJob(jContext);
    committer.setupTask(tContext);

    // write output
    MapFileOutputFormat theOutputFormat = new MapFileOutputFormat();
    RecordWriter theRecordWriter = theOutputFormat.getRecordWriter(tContext);
    writeMapFileOutput(theRecordWriter, tContext);

    // do commit
    committer.commitTask(tContext);
    committer.commitJob(jContext);

    // Ensure getReaders call works and also ignores
    // hidden filenames (_ or . prefixes)
    MapFile.Reader[] readers = {};
    try {
      readers = MapFileOutputFormat.getReaders(outDir, conf);
      // validate output
      validateMapFileOutputContent(FileSystem.get(job.getConfiguration()), outDir);
    } finally {
      IOUtils.cleanupWithLogger(null, readers);
      FileUtil.fullyDelete(new File(outDir.toString()));
    }
  }

  @Test
  public void testMapFileOutputCommitterV1() throws Exception {
    testMapFileOutputCommitterInternal(1);
  }

  @Test
  public void testMapFileOutputCommitterV2() throws Exception {
    testMapFileOutputCommitterInternal(2);
  }

  @Test
  public void testInvalidVersionNumber() throws IOException {
    Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION, 3);
    TaskAttemptContext tContext = new TaskAttemptContextImpl(conf, taskID);
    try {
      new FileOutputCommitter(outDir, tContext);
      fail("should've thrown an exception!");
    } catch (IOException e) {
      //test passed
    }
  }

  private void testAbortInternal(int version)
      throws IOException, InterruptedException {
    Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        version);
    JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
    TaskAttemptContext tContext = new TaskAttemptContextImpl(conf, taskID);
    FileOutputCommitter committer = new FileOutputCommitter(outDir, tContext);

    // do setup
    committer.setupJob(jContext);
    committer.setupTask(tContext);

    // write output
    TextOutputFormat theOutputFormat = new TextOutputFormat();
    RecordWriter theRecordWriter = theOutputFormat.getRecordWriter(tContext);
    writeOutput(theRecordWriter, tContext);

    // do abort
    committer.abortTask(tContext);
    File expectedFile = new File(new Path(committer.getWorkPath(), partFile)
        .toString());
    assertFalse(expectedFile.exists(), "task temp dir still exists");

    committer.abortJob(jContext, JobStatus.State.FAILED);
    expectedFile = new File(new Path(outDir, FileOutputCommitter.PENDING_DIR_NAME)
        .toString());
    assertFalse(expectedFile.exists(), "job temp dir still exists");
    assertEquals(0, new File(outDir.toString())
        .listFiles().length, "Output directory not empty");
    FileUtil.fullyDelete(new File(outDir.toString()));
  }

  @Test
  public void testAbortV1() throws IOException, InterruptedException {
    testAbortInternal(1);
  }

  @Test
  public void testAbortV2() throws IOException, InterruptedException {
    testAbortInternal(2);
  }

  public static class FakeFileSystem extends RawLocalFileSystem {
    public FakeFileSystem() {
      super();
    }

    public URI getUri() {
      return URI.create("faildel:///");
    }

    @Override
    public boolean delete(Path p, boolean recursive) throws IOException {
      throw new IOException("fake delete failed");
    }
  }


  private void testFailAbortInternal(int version)
      throws IOException, InterruptedException {
    Job job = Job.getInstance();
    Configuration conf = job.getConfiguration();
    conf.set(FileSystem.FS_DEFAULT_NAME_KEY, "faildel:///");
    conf.setClass("fs.faildel.impl", FakeFileSystem.class, FileSystem.class);
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(MRJobConfig.APPLICATION_ATTEMPT_ID, 1);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        version);
    FileOutputFormat.setOutputPath(job, outDir);
    JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
    TaskAttemptContext tContext = new TaskAttemptContextImpl(conf, taskID);
    FileOutputCommitter committer = new FileOutputCommitter(outDir, tContext);

    // do setup
    committer.setupJob(jContext);
    committer.setupTask(tContext);

    // write output
    TextOutputFormat<?, ?> theOutputFormat = new TextOutputFormat();
    RecordWriter<?, ?> theRecordWriter = theOutputFormat
        .getRecordWriter(tContext);
    writeOutput(theRecordWriter, tContext);

    // do abort
    Throwable th = null;
    try {
      committer.abortTask(tContext);
    } catch (IOException ie) {
      th = ie;
    }
    assertNotNull(th);
    assertTrue(th instanceof IOException);
    assertTrue(th.getMessage().contains("fake delete failed"));
    Path jtd = committer.getJobAttemptPath(jContext);
    File jobTmpDir = new File(jtd.toUri().getPath());
    Path ttd = committer.getTaskAttemptPath(tContext);
    File taskTmpDir = new File(ttd.toUri().getPath());
    File expectedFile = new File(taskTmpDir, partFile);
    assertTrue(expectedFile.exists(), expectedFile + " does not exists");

    th = null;
    try {
      committer.abortJob(jContext, JobStatus.State.FAILED);
    } catch (IOException ie) {
      th = ie;
    }
    assertNotNull(th);
    assertTrue(th instanceof IOException);
    assertTrue(th.getMessage().contains("fake delete failed"));
    assertTrue(jobTmpDir.exists(), "job temp dir does not exists");
    FileUtil.fullyDelete(new File(outDir.toString()));
  }

  @Test
  public void testFailAbortV1() throws Exception {
    testFailAbortInternal(1);
  }

  @Test
  public void testFailAbortV2() throws Exception {
    testFailAbortInternal(2);
  }

  static class RLFS extends RawLocalFileSystem {
    private final ThreadLocal<Boolean> needNull = new ThreadLocal<Boolean>() {
      @Override
      protected Boolean initialValue() {
        return true;
      }
    };

    public RLFS() {
    }

    @Override
    public FileStatus getFileStatus(Path f) throws IOException {
      if (needNull.get() &&
          OUT_SUB_DIR.toUri().getPath().equals(f.toUri().getPath())) {
        needNull.set(false); // lie once per thread
        return null;
      }
      return super.getFileStatus(f);
    }
  }

  private void testConcurrentCommitTaskWithSubDir(int version)
      throws Exception {
    final Job job = Job.getInstance();
    FileOutputFormat.setOutputPath(job, outDir);
    final Configuration conf = job.getConfiguration();
    conf.set(MRJobConfig.TASK_ATTEMPT_ID, attempt);
    conf.setInt(FileOutputCommitter.FILEOUTPUTCOMMITTER_ALGORITHM_VERSION,
        version);

    final String fileImpl = "fs.file.impl";
    final String fileImplClassname = "org.apache.hadoop.fs.LocalFileSystem";
    conf.setClass(fileImpl, RLFS.class, FileSystem.class);
    FileSystem.closeAll();

    try {
      final JobContext jContext = new JobContextImpl(conf, taskID.getJobID());
      final FileOutputCommitter amCommitter =
          new FileOutputCommitter(outDir, jContext);
      amCommitter.setupJob(jContext);

      final TaskAttemptContext[] taCtx = new TaskAttemptContextImpl[2];
      taCtx[0] = new TaskAttemptContextImpl(conf, taskID);
      taCtx[1] = new TaskAttemptContextImpl(conf, taskID1);

      final TextOutputFormat[] tof = new TextOutputFormat[2];
      for (int i = 0; i < tof.length; i++) {
        tof[i] = new TextOutputFormat() {
          @Override
          public Path getDefaultWorkFile(TaskAttemptContext context,
              String extension) throws IOException {
            final FileOutputCommitter foc = (FileOutputCommitter)
                getOutputCommitter(context);
            return new Path(new Path(foc.getWorkPath(), SUB_DIR),
                getUniqueFile(context, getOutputName(context), extension));
          }
        };
      }

      final ExecutorService executor = HadoopExecutors.newFixedThreadPool(2);
      try {
        for (int i = 0; i < taCtx.length; i++) {
          final int taskIdx = i;
          executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws IOException, InterruptedException {
              final OutputCommitter outputCommitter =
                  tof[taskIdx].getOutputCommitter(taCtx[taskIdx]);
              outputCommitter.setupTask(taCtx[taskIdx]);
              final RecordWriter rw =
                  tof[taskIdx].getRecordWriter(taCtx[taskIdx]);
              writeOutput(rw, taCtx[taskIdx]);
              outputCommitter.commitTask(taCtx[taskIdx]);
              return null;
            }
          });
        }
      } finally {
        executor.shutdown();
        while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
          LOG.info("Awaiting thread termination!");
        }
      }

      amCommitter.commitJob(jContext);
      final RawLocalFileSystem lfs = new RawLocalFileSystem();
      lfs.setConf(conf);
      assertFalse(lfs.exists(new Path(OUT_SUB_DIR, SUB_DIR)),
          "Must not end up with sub_dir/sub_dir");

      // validate output
      validateContent(OUT_SUB_DIR);
      FileUtil.fullyDelete(new File(outDir.toString()));
    } finally {
      // needed to avoid this test contaminating others in the same JVM
      FileSystem.closeAll();
      conf.set(fileImpl, fileImplClassname);
    }
  }

  @Test
  public void testConcurrentCommitTaskWithSubDirV1() throws Exception {
    testConcurrentCommitTaskWithSubDir(1);
  }

  @Test
  public void testConcurrentCommitTaskWithSubDirV2() throws Exception {
    testConcurrentCommitTaskWithSubDir(2);
  }

  public static String slurp(File f) throws IOException {
    int len = (int) f.length();
    byte[] buf = new byte[len];
    FileInputStream in = new FileInputStream(f);
    String contents = null;
    try {
      in.read(buf, 0, len);
      contents = new String(buf, StandardCharsets.UTF_8);
    } finally {
      in.close();
    }
    return contents;
  }

  /**
   * The class provides a overrided implementation of commitJobInternal which
   * causes the commit failed for the first time then succeed.
   */
  public static class CommitterWithFailedThenSucceed extends
      FileOutputCommitter {
    boolean firstTimeFail = true;

    public CommitterWithFailedThenSucceed(Path outputPath,
        JobContext context) throws IOException {
      super(outputPath, context);
    }

    @Override
    protected void commitJobInternal(JobContext context) throws IOException {
      super.commitJobInternal(context);
      if (firstTimeFail) {
        firstTimeFail = false;
        throw new IOException();
      } else {
        // succeed then, nothing to do
      }
    }
  }

}
