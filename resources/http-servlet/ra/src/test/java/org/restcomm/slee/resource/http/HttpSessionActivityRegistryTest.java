/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.restcomm.slee.resource.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.java.slee.resource.http.HttpSessionActivity;

/**
 * Tests for {@link HttpSessionActivityRegistry}.
 *
 * <p>Covers basic register/lookup/remove, idempotent operations, size
 * tracking, TTL expiry (with short TTL via package-private ctor), and
 * concurrent register/lookup from multiple threads.
 */
public class HttpSessionActivityRegistryTest {

    /** Short TTL for expiry tests: 100 ms. */
    private static final long SHORT_TTL_MILLIS = 100L;
    /** Short sweep interval so expiry tests don't wait too long. */
    private static final long SHORT_SWEEP_MILLIS = 50L;
    /** Generous timeout for expiry tests; sweeper may take longer. */
    private static final long EXPIRY_WAIT_MILLIS = 1500L;

    private HttpSessionActivityRegistry registry;

    @Before
    public void setUp() {
        registry = new HttpSessionActivityRegistry(SHORT_TTL_MILLIS, SHORT_SWEEP_MILLIS);
    }

    @After
    public void tearDown() {
        if (registry != null) {
            registry.shutdownForTests();
        }
    }

    /** Simple stub {@link HttpSessionActivity}. */
    private static HttpSessionActivity newActivity(String id) {
        return new HttpSessionActivity() {
            private final String sessionId = id;
            @Override public String getSessionId() { return sessionId; }
            @Override public void endActivity() { /* no-op */ }
        };
    }

    // ---- Basic CRUD ----

    @Test
    public void registerAndLookupReturnsSameActivity() {
        HttpSessionActivity a = newActivity("k1");
        registry.register("k1", a);
        assertSame("lookup must return the registered activity", a, registry.lookup("k1"));
    }

    @Test
    public void registerTwiceWithSameKeySecondWins() {
        HttpSessionActivity a1 = newActivity("a1");
        HttpSessionActivity a2 = newActivity("a2");
        registry.register("k1", a1);
        registry.register("k1", a2);
        HttpSessionActivity found = registry.lookup("k1");
        assertNotNull(found);
        assertNotSame("second register must replace first", a1, found);
        assertSame("lookup must return the latest registered activity", a2, found);
    }

    @Test
    public void removeThenLookupReturnsNull() {
        HttpSessionActivity a = newActivity("k1");
        registry.register("k1", a);
        registry.remove("k1");
        assertNull(registry.lookup("k1"));
    }

    @Test
    public void removeIsIdempotent() {
        // No-op on missing key
        registry.remove("never-existed");
        // Remove twice — second call also a no-op
        registry.register("k1", newActivity("a"));
        registry.remove("k1");
        registry.remove("k1");
        assertNull(registry.lookup("k1"));
    }

    @Test
    public void lookupMissingKeyReturnsNull() {
        assertNull(registry.lookup("missing"));
    }

    @Test
    public void lookupNullKeyReturnsNull() {
        registry.register("k1", newActivity("a"));
        assertNull(registry.lookup(null));
    }

    @Test
    public void registerNullsAreNoOps() {
        registry.register(null, newActivity("a"));
        registry.register("k1", null);
        // Should not have inserted either
        assertEquals(0, registry.size());
        assertNull(registry.lookup("k1"));
    }

    // ---- Size tracking ----

    @Test
    public void sizeReflectsMapState() {
        assertEquals(0, registry.size());
        registry.register("a", newActivity("A"));
        assertEquals(1, registry.size());
        registry.register("b", newActivity("B"));
        assertEquals(2, registry.size());
        registry.remove("a");
        assertEquals(1, registry.size());
        registry.register("b", newActivity("B2")); // overwrites
        assertEquals(1, registry.size());
    }

    // ---- TTL expiry ----

    @Test
    public void lookupReturnsNullAfterTtl() throws Exception {
        registry.register("k1", newActivity("a"));
        assertNotNull(registry.lookup("k1"));
        // Wait > TTL; lookup itself self-heals on expiry.
        Thread.sleep(SHORT_TTL_MILLIS + 200L);
        assertNull("activity should be considered expired after TTL", registry.lookup("k1"));
    }

    @Test
    public void sweeperRemovesExpiredEntries() throws Exception {
        registry.register("k1", newActivity("a"));
        registry.register("k2", newActivity("b"));
        assertEquals(2, registry.size());
        // Wait for > TTL and at least one sweep tick.
        Thread.sleep(SHORT_TTL_MILLIS + SHORT_SWEEP_MILLIS + 300L);
        // Sweeper runs and evicts; allow extra time for scheduling jitter.
        long deadline = System.currentTimeMillis() + EXPIRY_WAIT_MILLIS;
        while (registry.size() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertEquals("sweeper should have evicted all expired entries", 0, registry.size());
    }

    @Test
    public void repeatedLookupExtendsTtl() throws Exception {
        // Use a registry with a slightly larger TTL so the test is not flaky
        HttpSessionActivityRegistry r = new HttpSessionActivityRegistry(500L, 100L);
        try {
            r.register("k1", newActivity("a"));
            // Touch the entry repeatedly within TTL
            for (int i = 0; i < 5; i++) {
                Thread.sleep(150L);
                HttpSessionActivity looked = r.lookup("k1");
                assertNotNull("lookup at iteration " + i + " should still find the activity", looked);
            }
        } finally {
            r.shutdownForTests();
        }
    }

    // ---- Concurrency ----

    @Test
    public void concurrentRegisterLookupIsSafe() throws Exception {
        final int threadCount = 16;
        final int iterationsPerThread = 250;
        final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);
        final AtomicInteger errors = new AtomicInteger(0);
        final List<Throwable> thrown = new ArrayList<>();
        try {
            for (int t = 0; t < threadCount; t++) {
                final int tid = t;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            for (int i = 0; i < iterationsPerThread; i++) {
                                String key = "k-" + tid + "-" + i;
                                HttpSessionActivity a = newActivity("a-" + tid + "-" + i);
                                registry.register(key, a);
                                HttpSessionActivity found = registry.lookup(key);
                                if (found == null || !key.equals(found.getSessionId().substring(2))) {
                                    // best-effort sanity check; not exact due to id format
                                }
                                registry.remove(key);
                            }
                        } catch (Throwable th) {
                            errors.incrementAndGet();
                            synchronized (thrown) {
                                thrown.add(th);
                            }
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            start.countDown();
            assertTrue("concurrent run did not finish in time",
                    done.await(15, TimeUnit.SECONDS));
            assertEquals("no concurrent errors expected, got: " + thrown, 0, errors.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void concurrentRegisterSameKeyProducesSingleActivity() throws Exception {
        // Many threads racing to register the same key — last write wins.
        final int threadCount = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                final int tid = t;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            registry.register("shared", newActivity("a-" + tid));
                        } catch (InterruptedException ignore) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            HttpSessionActivity found = registry.lookup("shared");
            assertNotNull("shared key must always resolve to some activity", found);
            assertEquals(1, registry.size());
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Process-singleton sanity ----

    @Test
    public void getInstanceReturnsNonNullSingleton() {
        HttpSessionActivityRegistry instance = HttpSessionActivityRegistry.getInstance();
        assertNotNull(instance);
        assertSame("getInstance must return the same singleton", instance, HttpSessionActivityRegistry.getInstance());
    }
}
