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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;

import static java.lang.Thread.sleep;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.SERVICE_SHUTDOWN_TIMEOUT;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.SERVICE_SHUTDOWN_TIMEOUT_DEFAULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestShutdownHookManager {

  static final Logger LOG =
      LoggerFactory.getLogger(TestShutdownHookManager.class.getName());

  /**
   * A new instance of ShutdownHookManager to ensure parallel tests
   * don't have shared context.
   */
  private final ShutdownHookManager mgr = new ShutdownHookManager();

  /**
   * Verify hook registration, then execute the hook callback stage
   * of shutdown to verify invocation, execution order and timeout
   * processing.
   */
  @Test
  public void shutdownHookManager() {
    assertNotNull(mgr, "No ShutdownHookManager");
    assertEquals(0, mgr.getShutdownHooksInOrder().size());
    Hook hook1 = new Hook("hook1", 0, false);
    Hook hook2 = new Hook("hook2", 0, false);
    Hook hook3 = new Hook("hook3", 1000, false);
    Hook hook4 = new Hook("hook4", 25000, true);
    Hook hook5 = new Hook("hook5",
        (SERVICE_SHUTDOWN_TIMEOUT_DEFAULT + 1) * 1000, true);

    mgr.addShutdownHook(hook1, 0);
    assertTrue(mgr.hasShutdownHook(hook1));
    assertEquals(1, mgr.getShutdownHooksInOrder().size());
    assertEquals(hook1, mgr.getShutdownHooksInOrder().get(0).getHook());
    assertTrue(mgr.removeShutdownHook(hook1));
    assertFalse(mgr.hasShutdownHook(hook1));
    assertFalse(mgr.removeShutdownHook(hook1));

    mgr.addShutdownHook(hook1, 0);
    assertTrue(mgr.hasShutdownHook(hook1));
    assertEquals(1, mgr.getShutdownHooksInOrder().size());
    assertEquals(SERVICE_SHUTDOWN_TIMEOUT_DEFAULT,
        mgr.getShutdownHooksInOrder().get(0).getTimeout());

    mgr.addShutdownHook(hook2, 1);
    assertTrue(mgr.hasShutdownHook(hook1));
    assertTrue(mgr.hasShutdownHook(hook2));
    assertEquals(2, mgr.getShutdownHooksInOrder().size());
    assertEquals(hook2, mgr.getShutdownHooksInOrder().get(0).getHook());
    assertEquals(hook1, mgr.getShutdownHooksInOrder().get(1).getHook());

    // Test hook finish without timeout
    mgr.addShutdownHook(hook3, 2, 4, TimeUnit.SECONDS);
    assertTrue(mgr.hasShutdownHook(hook3));
    assertEquals(hook3, mgr.getShutdownHooksInOrder().get(0).getHook());
    assertEquals(4, mgr.getShutdownHooksInOrder().get(0).getTimeout());

    // Test hook finish with timeout; highest priority
    int hook4timeout = 2;
    mgr.addShutdownHook(hook4, 3, hook4timeout, TimeUnit.SECONDS);
    assertTrue(mgr.hasShutdownHook(hook4));
    assertEquals(hook4, mgr.getShutdownHooksInOrder().get(0).getHook());
    assertEquals(2, mgr.getShutdownHooksInOrder().get(0).getTimeout());

    // a default timeout hook and verify it gets the default timeout
    mgr.addShutdownHook(hook5, 5);
    ShutdownHookManager.HookEntry hookEntry5 = mgr.getShutdownHooksInOrder()
        .get(0);
    assertEquals(hook5, hookEntry5.getHook());
    assertEquals(ShutdownHookManager.getShutdownTimeout(new Configuration()),
        hookEntry5.getTimeout(), "default timeout not used");
    assertEquals(5, hookEntry5.getPriority(), "hook priority");
    // remove this to avoid a longer sleep in the test run
    assertTrue(mgr.removeShutdownHook(hook5), "failed to remove " + hook5);


    // now execute the hook shutdown sequence
    INVOCATION_COUNT.set(0);
    LOG.info("invoking executeShutdown()");
    int timeouts = mgr.executeShutdown();
    LOG.info("Shutdown completed");
    assertEquals(1, timeouts, "Number of timed out hooks");

    List<ShutdownHookManager.HookEntry> hooks
        = mgr.getShutdownHooksInOrder();

    // analyze the hooks
    for (ShutdownHookManager.HookEntry entry : hooks) {
      Hook hook = (Hook) entry.getHook();
      assertTrue(hook.invoked, "Was not invoked " + hook);
      // did any hook raise an exception?
      hook.maybeThrowAssertion();
    }

    // check the state of some of the invoked hooks
    // hook4 was invoked first, but it timed out.
    assertEquals(1, hook4.invokedOrder, "Expected to be invoked first " + hook4);
    assertFalse(hook4.completed, "Expected to time out " + hook4);


    // hook1 completed, but in order after the others, so its start time
    // is the longest.
    assertTrue(hook1.completed, "Expected to complete " + hook1);
    long invocationInterval = hook1.startTime - hook4.startTime;
    assertTrue(invocationInterval >= hook4timeout * 1000,
        "invocation difference too short " + invocationInterval);
    assertTrue(invocationInterval < hook4.sleepTime,
        "sleeping hook4 blocked other threads for " + invocationInterval);

    // finally, clear the hooks
    mgr.clearShutdownHooks();
    // and verify that the hooks are empty
    assertFalse(mgr.hasShutdownHook(hook1));
    assertEquals(0, mgr.getShutdownHooksInOrder().size(),
        "shutdown hook list is not empty");
  }

  @Test
  public void testShutdownTimeoutConfiguration() throws Throwable {
    // set the shutdown timeout and verify it can be read back.
    Configuration conf = new Configuration();
    long shutdownTimeout = 5;
    conf.setTimeDuration(SERVICE_SHUTDOWN_TIMEOUT,
        shutdownTimeout, TimeUnit.SECONDS);
    assertEquals(shutdownTimeout, ShutdownHookManager.getShutdownTimeout(conf),
        SERVICE_SHUTDOWN_TIMEOUT);
  }

  /**
   * Verify that low timeouts simply fall back to
   * {@link ShutdownHookManager#TIMEOUT_MINIMUM}.
   */
  @Test
  public void testShutdownTimeoutBadConfiguration() throws Throwable {
    // set the shutdown timeout and verify it can be read back.
    Configuration conf = new Configuration();
    long shutdownTimeout = 50;
    conf.setTimeDuration(SERVICE_SHUTDOWN_TIMEOUT,
        shutdownTimeout, TimeUnit.NANOSECONDS);
    assertEquals(ShutdownHookManager.TIMEOUT_MINIMUM,
        ShutdownHookManager.getShutdownTimeout(conf), SERVICE_SHUTDOWN_TIMEOUT);
  }

  /**
   * Verifies that a hook cannot be re-registered: an attempt to do so
   * will simply be ignored.
   */
  @Test
  public void testDuplicateRegistration() throws Throwable {
    Hook hook = new Hook("hook1", 0, false);

    // add the hook
    mgr.addShutdownHook(hook, 2, 1, TimeUnit.SECONDS);

    // add it at a higher priority. This will be ignored.
    mgr.addShutdownHook(hook, 5);
    List<ShutdownHookManager.HookEntry> hookList
        = mgr.getShutdownHooksInOrder();
    assertEquals(1, hookList.size(), "Hook added twice");
    ShutdownHookManager.HookEntry entry = hookList.get(0);
    assertEquals(2, entry.getPriority(), "priority of hook");
    assertEquals(1, entry.getTimeout(), "timeout of hook");

    // remove the hook
    assertTrue(mgr.removeShutdownHook(hook), "failed to remove hook " + hook);
    // which will fail a second time
    assertFalse(mgr.removeShutdownHook(hook), "expected hook removal to fail");

    // now register it
    mgr.addShutdownHook(hook, 5);
    hookList = mgr.getShutdownHooksInOrder();
    entry = hookList.get(0);
    assertEquals(5, entry.getPriority(), "priority of hook");
    assertNotEquals(1, entry.getTimeout(), "timeout of hook");

  }

  @Test
  public void testShutdownRemove() throws Throwable {
    assertNotNull(mgr, "No ShutdownHookManager");
    assertEquals(0, mgr.getShutdownHooksInOrder().size());
    Hook hook1 = new Hook("hook1", 0, false);
    Hook hook2 = new Hook("hook2", 0, false);
    mgr.addShutdownHook(hook1, 9); // create Hook1 with priority 9
    assertTrue(mgr.hasShutdownHook(hook1), "No hook1"); // hook1 lookup works
    assertEquals(1, mgr.getShutdownHooksInOrder().size()); // 1 hook
    assertFalse(mgr.removeShutdownHook(hook2),
        "Delete hook2 should not be allowed");
    assertTrue(mgr.removeShutdownHook(hook1), "Can't delete hook1");
    assertEquals(0, mgr.getShutdownHooksInOrder().size());
  }

  private static final AtomicInteger INVOCATION_COUNT = new AtomicInteger();

  /**
   * Hooks for testing; save state for ease of asserting on
   * invocation.
   */
  private class Hook implements Runnable {

    private final String name;
    private final long sleepTime;
    private final boolean expectFailure;
    private AssertionError assertion;
    private boolean invoked;
    private int invokedOrder;
    private boolean completed;
    private boolean interrupted;
    private long startTime;

    Hook(final String name,
        final long sleepTime,
        final boolean expectFailure) {
      this.name = name;
      this.sleepTime = sleepTime;
      this.expectFailure = expectFailure;
    }

    @Override
    public void run() {
      try {
        invoked = true;
        invokedOrder = INVOCATION_COUNT.incrementAndGet();
        startTime = System.currentTimeMillis();
        LOG.info("Starting shutdown of {} with sleep time of {}",
            name, sleepTime);
        if (sleepTime > 0) {
          sleep(sleepTime);
        }
        LOG.info("Completed shutdown of {}", name);
        completed = true;
        if (expectFailure) {
          assertion = new AssertionError("Expected a failure of " + name);
        }
      } catch (InterruptedException ex) {
        LOG.info("Shutdown {} interrupted exception", name, ex);
        interrupted = true;
        if (!expectFailure) {
          assertion = new AssertionError("Timeout of " + name, ex);
        }
      }
      maybeThrowAssertion();
    }

    /**
     * Raise any exception generated during the shutdown process.
     * @throws AssertionError any assertion from the shutdown.
     */
    void maybeThrowAssertion() throws AssertionError {
      if (assertion != null) {
        throw assertion;
      }
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder("Hook{");
      sb.append("name='").append(name).append('\'');
      sb.append(", sleepTime=").append(sleepTime);
      sb.append(", expectFailure=").append(expectFailure);
      sb.append(", invoked=").append(invoked);
      sb.append(", invokedOrder=").append(invokedOrder);
      sb.append(", completed=").append(completed);
      sb.append(", interrupted=").append(interrupted);
      sb.append('}');
      return sb.toString();
    }
  }
}
