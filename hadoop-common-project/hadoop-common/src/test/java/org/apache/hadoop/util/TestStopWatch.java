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
package org.apache.hadoop.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestStopWatch {

  @Test
  public void testStartAndStop() throws Exception {
    try (StopWatch sw = new StopWatch()) {
      assertFalse(sw.isRunning());
      sw.start();
      assertTrue(sw.isRunning());
      sw.stop();
      assertFalse(sw.isRunning());
    }
  }

  @Test
  public void testStopInTryWithResource() throws Exception {
    try (StopWatch sw = new StopWatch()) {
      // make sure that no exception is thrown.
    }
  }

  @Test
  public void testExceptions() throws Exception {
    StopWatch sw = new StopWatch();
    try {
      sw.stop();
    } catch (Exception e) {
      assertTrue(e instanceof IllegalStateException,
          "IllegalStateException is expected");
    }
    sw.reset();
    sw.start();
    try {
      sw.start();
    } catch (Exception e) {
      assertTrue(e instanceof IllegalStateException,
          "IllegalStateException is expected");
    }
  }

}
