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
package org.apache.hadoop.security.alias;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.ProviderUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestCredentialProviderFactory {
  public static final Logger LOG =
      LoggerFactory.getLogger(TestCredentialProviderFactory.class);

  @BeforeEach
  public void announce(TestInfo testInfo) {
    LOG.info("Running test " + testInfo.getDisplayName());
  }

  private static char[] chars = { 'a', 'b', 'c', 'd', 'e', 'f', 'g',
  'h', 'j', 'k', 'm', 'n', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w',
  'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K',
  'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
  '2', '3', '4', '5', '6', '7', '8', '9',};

  private static final File tmpDir = GenericTestUtils.getTestDir("creds");

  @Test
  public void testFactory() throws Exception {
    Configuration conf = new Configuration();
    final String userUri = UserProvider.SCHEME_NAME + ":///";
    final Path jksPath = new Path(tmpDir.toString(), "test.jks");
    final String jksUri = JavaKeyStoreProvider.SCHEME_NAME +
        "://file" + jksPath.toUri();
    conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH,
        userUri + "," + jksUri);
    List<CredentialProvider> providers = 
        CredentialProviderFactory.getProviders(conf);
    assertEquals(2, providers.size());
    assertEquals(UserProvider.class, providers.get(0).getClass());
    assertEquals(JavaKeyStoreProvider.class, providers.get(1).getClass());
    assertEquals(userUri, providers.get(0).toString());
    assertEquals(jksUri, providers.get(1).toString());
  }

  @Test
  public void testFactoryErrors() throws Exception {
    Configuration conf = new Configuration();
    conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, "unknown:///");
    try {
      List<CredentialProvider> providers = 
          CredentialProviderFactory.getProviders(conf);
      assertTrue(false, "should throw!");
    } catch (IOException e) {
      assertEquals("No CredentialProviderFactory for unknown:/// in " +
          CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH,
          e.getMessage());
    }
  }

  @Test
  public void testUriErrors() throws Exception {
    Configuration conf = new Configuration();
    conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, "unkn@own:/x/y");
    try {
      List<CredentialProvider> providers = 
          CredentialProviderFactory.getProviders(conf);
      assertTrue(false, "should throw!");
    } catch (IOException e) {
      assertEquals("Bad configuration of " +
          CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH +
          " at unkn@own:/x/y", e.getMessage());
    }
  }

  private static char[] generatePassword(int length) {
    StringBuilder sb = new StringBuilder();
    Random r = new Random();
    for (int i = 0; i < length; i++) {
      sb.append(chars[r.nextInt(chars.length)]);
    }
    return sb.toString().toCharArray();
  }
  
  static void checkSpecificProvider(Configuration conf,
                                   String ourUrl) throws Exception {
    CredentialProvider provider = 
        CredentialProviderFactory.getProviders(conf).get(0);
    char[] passwd = generatePassword(16);

    // ensure that we get nulls when the key isn't there
    assertEquals(null, provider.getCredentialEntry("no-such-key"));
    assertEquals(null, provider.getCredentialEntry("key"));
    // create a new key
    try {
      provider.createCredentialEntry("pass", passwd);
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
    // make sure we get back the right key
    assertArrayEquals(passwd, provider.getCredentialEntry("pass").getCredential());
    // try recreating pass
    try {
      provider.createCredentialEntry("pass", passwd);
      assertTrue(false, "should throw");
    } catch (IOException e) {
      assertEquals("Credential pass already exists in " + ourUrl, e.getMessage());
    }
    provider.deleteCredentialEntry("pass");
    try {
      provider.deleteCredentialEntry("pass");
      assertTrue(false, "should throw");
    } catch (IOException e) {
      assertEquals("Credential pass does not exist in " + ourUrl, e.getMessage());
    }
    char[] passTwo = new char[]{'1', '2', '3'};
    provider.createCredentialEntry("pass", passwd);
    provider.createCredentialEntry("pass2", passTwo);
    assertArrayEquals(passTwo,
        provider.getCredentialEntry("pass2").getCredential());

    // write them to disk so that configuration.getPassword will find them
    provider.flush();

    // configuration.getPassword should get this from provider
    assertArrayEquals(passTwo, conf.getPassword("pass2"));

    // configuration.getPassword should get this from config
    conf.set("onetwothree", "123");
    assertArrayEquals(passTwo, conf.getPassword("onetwothree"));

    // configuration.getPassword should NOT get this from config since
    // we are disabling the fallback to clear text config
    conf.set(CredentialProvider.CLEAR_TEXT_FALLBACK, "false");
    assertArrayEquals(null, conf.getPassword("onetwothree"));

    // get a new instance of the provider to ensure it was saved correctly
    provider = CredentialProviderFactory.getProviders(conf).get(0);
    assertTrue(provider != null);
    assertArrayEquals(new char[]{'1', '2', '3'},
        provider.getCredentialEntry("pass2").getCredential());
    assertArrayEquals(passwd, provider.getCredentialEntry("pass").getCredential());

    List<String> creds = provider.getAliases();
    assertTrue(creds.size() == 2, "Credentials should have been returned.");
    assertTrue(creds.contains("pass"), "Returned Credentials should have included pass.");
    assertTrue(creds.contains("pass2"), "Returned Credentials should have included pass2.");
  }

  @Test
  public void testUserProvider() throws Exception {
    Configuration conf = new Configuration();
    final String ourUrl = UserProvider.SCHEME_NAME + ":///";
    conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, ourUrl);
    checkSpecificProvider(conf, ourUrl);
    // see if the credentials are actually in the UGI
    Credentials credentials =
        UserGroupInformation.getCurrentUser().getCredentials();
    assertArrayEquals(new byte[]{'1', '2', '3'},
        credentials.getSecretKey(new Text("pass2")));
  }

  @Test
  public void testJksProvider() throws Exception {
    Configuration conf = new Configuration();
    final Path jksPath = new Path(tmpDir.toString(), "test.jks");
    final String ourUrl =
        JavaKeyStoreProvider.SCHEME_NAME + "://file" + jksPath.toUri();

    File file = new File(tmpDir, "test.jks");
    file.delete();
    conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, ourUrl);
    checkSpecificProvider(conf, ourUrl);
    Path path = ProviderUtils.unnestUri(new URI(ourUrl));
    FileSystem fs = path.getFileSystem(conf);
    FileStatus s = fs.getFileStatus(path);
    assertEquals("rw-------", s.getPermission().toString());
    assertTrue(file.isFile(), file + " should exist");

    // check permission retention after explicit change
    fs.setPermission(path, new FsPermission("777"));
    checkPermissionRetention(conf, ourUrl, path);
  }

  @Test
  public void testLocalJksProvider() throws Exception {
    Configuration conf = new Configuration();
    final Path jksPath = new Path(tmpDir.toString(), "test.jks");
    final String ourUrl =
        LocalJavaKeyStoreProvider.SCHEME_NAME + "://file" + jksPath.toUri();

    File file = new File(tmpDir, "test.jks");
    file.delete();
    conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, ourUrl);
    checkSpecificProvider(conf, ourUrl);
    Path path = ProviderUtils.unnestUri(new URI(ourUrl));
    FileSystem fs = path.getFileSystem(conf);
    FileStatus s = fs.getFileStatus(path);
    assertEquals("rw-------", s.getPermission().toString(),
        "Unexpected permissions: " + s.getPermission().toString());
    assertTrue(file.isFile(), file + " should exist");

    // check permission retention after explicit change
    fs.setPermission(path, new FsPermission("777"));
    checkPermissionRetention(conf, ourUrl, path);
  }

  @Test
  public void testLocalBCFKSProvider() {
    Configuration conf = new Configuration();
    final Path ksPath = new Path(tmpDir.toString(), "test.bcfks");
    final String ourUrl = LocalBouncyCastleFipsKeyStoreProvider.SCHEME_NAME +
        "://file" + ksPath.toUri();
    conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, ourUrl);
    Exception exception = assertThrows(IOException.class,
        () -> CredentialProviderFactory.getProviders(conf));
    assertEquals("Can't create keystore", exception.getMessage());
  }

  public void checkPermissionRetention(Configuration conf, String ourUrl,
      Path path) throws Exception {
    CredentialProvider provider = CredentialProviderFactory.getProviders(conf).get(0);
    // let's add a new credential and flush and check that permissions are still set to 777
    char[] cred = new char[32];
    for(int i =0; i < cred.length; ++i) {
      cred[i] = (char) i;
    }
    // create a new key
    try {
      provider.createCredentialEntry("key5", cred);
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
    provider.flush();
    // get a new instance of the provider to ensure it was saved correctly
    provider = CredentialProviderFactory.getProviders(conf).get(0);
    assertArrayEquals(cred, provider.getCredentialEntry("key5").getCredential());

    FileSystem fs = path.getFileSystem(conf);
    FileStatus s = fs.getFileStatus(path);
    assertEquals("rwxrwxrwx", s.getPermission().toString(),
        "Permissions should have been retained from the preexisting keystore.");
  }
}
