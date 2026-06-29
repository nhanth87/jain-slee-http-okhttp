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

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.java.slee.resource.http.HttpSessionActivity;

/**
 * Process-local registry mapping a session key (either a
 * {@code X-Ussd-Session-Id} header value or a {@code JSESSIONID} cookie
 * value, depending on {@link SessionStrategy}) to a live
 * {@link HttpSessionActivity}.
 *
 * <p>This replaces the servlet container's session pool as the source of
 * truth for multi-turn USSD correlation. The RA calls
 * {@link #register(String, HttpSessionActivity)} once when the activity
 * is created and {@link #remove(String)} when it ends. Concurrent
 * requests with the same key resolve to the same activity via
 * {@link #lookup(String)}.
 *
 * <p>Entries expire automatically after a configurable TTL (default 10
 * minutes) and are reaped by a daemon thread running every 60 seconds.
 * The TTL is conservative — a real session lifecycle should call
 * {@link #remove(String)} explicitly; the sweeper only handles leaks.
 *
 * <p>This class is process-local by design (cluster sync is out of
 * scope and matches the existing single-process behaviour of the
 * legacy JSESSIONID approach).
 *
 * @author HYBRID strategy (SESS-1)
 */
public final class HttpSessionActivityRegistry {

    /** Default TTL for an inactive entry, in milliseconds. */
    public static final long DEFAULT_TTL_MILLIS = 600_000L; // 10 min

    /** Sweep interval for the daemon thread, in milliseconds. */
    public static final long DEFAULT_SWEEP_INTERVAL_MILLIS = 60_000L; // 1 min

    private static final HttpSessionActivityRegistry INSTANCE = new HttpSessionActivityRegistry(
            DEFAULT_TTL_MILLIS, DEFAULT_SWEEP_INTERVAL_MILLIS);

    /** session key -> registry entry */
    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();

    private final long ttlMillis;
    private final long sweepIntervalMillis;
    private final ScheduledExecutorService sweeper;

    /**
     * Package-private constructor for tests. Use
     * {@link #getInstance()} for the process singleton.
     */
    HttpSessionActivityRegistry(long ttlMillis, long sweepIntervalMillis) {
        this.ttlMillis = ttlMillis;
        this.sweepIntervalMillis = sweepIntervalMillis;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
        this.sweeper.scheduleWithFixedDelay(new SweepTask(),
                this.sweepIntervalMillis, this.sweepIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the process-wide singleton. The TTL/interval are
     * constants here — overridable only via the test-only constructor.
     */
    public static HttpSessionActivityRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Lookup activity by session key. Returns {@code null} if missing
     * or expired.
     */
    public HttpSessionActivity lookup(String sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        Entry e = sessions.get(sessionKey);
        if (e == null) {
            return null;
        }
        if (isExpired(e)) {
            // Lazy self-heal on miss after expiry; don't take the lock
            // because the sweeper will catch up.
            sessions.remove(sessionKey, e);
            return null;
        }
        // Touch to extend the inactivity window on every lookup.
        e.touch();
        return e.activity;
    }

    /**
     * Register a new session key → activity mapping. Overwrites any
     * prior mapping with the same key (idempotent for callers that
     * re-register, e.g. on cluster failover).
     */
    public void register(String sessionKey, HttpSessionActivity activity) {
        if (sessionKey == null || activity == null) {
            return;
        }
        sessions.put(sessionKey, new Entry(activity, System.currentTimeMillis() + ttlMillis, ttlMillis));
    }

    /**
     * Remove a session entry. Idempotent: removing a non-existent key
     * is a no-op.
     */
    public void remove(String sessionKey) {
        if (sessionKey == null) {
            return;
        }
        sessions.remove(sessionKey);
    }

    /**
     * Approximate size of the registry. For metrics / health checks
     * only — concurrent mutations may make this briefly inaccurate.
     */
    public int size() {
        return sessions.size();
    }

    /**
     * Test-only: shuts down the sweeper. Production code must NOT call
     * this; the singleton's sweeper is a daemon that dies with the JVM.
     */
    void shutdownForTests() {
        sweeper.shutdownNow();
    }

    private boolean isExpired(Entry e) {
        return System.currentTimeMillis() > e.expiresAtMillis;
    }

    /** Internal holder. Immutable except for {@link #touch()}. */
    private static final class Entry {
        final HttpSessionActivity activity;
        final long ttlMillis;
        volatile long expiresAtMillis;

        Entry(HttpSessionActivity activity, long expiresAtMillis, long ttlMillis) {
            this.activity = activity;
            this.expiresAtMillis = expiresAtMillis;
            this.ttlMillis = ttlMillis;
        }

        /** Re-arm the expiry window on access. */
        void touch() {
            this.expiresAtMillis = System.currentTimeMillis() + ttlMillis;
        }
    }

    /** Daemon thread factory for the sweeper. */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicLong tid = new AtomicLong(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "http-session-registry-sweeper-" + tid.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        }
    }

    /** Periodic background sweep that removes expired entries. */
    private final class SweepTask implements Runnable {
        @Override
        public void run() {
            try {
                final long now = System.currentTimeMillis();
                final Iterator<Map.Entry<String, Entry>> it = sessions.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Entry> me = it.next();
                    if (me.getValue() == null) {
                        it.remove();
                        continue;
                    }
                    if (now > me.getValue().expiresAtMillis) {
                        it.remove();
                    }
                }
            } catch (Throwable t) {
                // never let the sweeper die silently — surface in the
                // next log scan, but keep the loop alive.
                t.printStackTrace();
            }
        }
    }
}