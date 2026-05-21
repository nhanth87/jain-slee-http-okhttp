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

package org.restcomm.client.slee.resource.http;

import java.util.concurrent.TimeUnit;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClients;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * <p>
 * High-performance OkHttp Client Factory for JAIN SLEE HTTP RA.
 * </p>
 * 
 * <p>
 * Configuration for bulk data optimization:
 * - Connection pool: 100,000 max idle connections
 * - Keep-alive: 5 minutes
 * - Dispatcher: 100,000 max requests, 1000 concurrent per host
 * - Timeouts: Connect 5s, Read 30s, Write 30s
 * - HTTP/2 enabled for multiplexed connections
 * </p>
 * 
 * <p>
 * To use this factory, configure the HTTP RA with:
 * HTTP_CLIENT_FACTORY = org.restcomm.client.slee.resource.http.OkHttpClientFactory
 * </p>
 * 
 * @author Matrix Agent (Optimization)
 */
public class OkHttpClientFactory implements HttpClientFactory {

    // HIGH PERFORMANCE: 100k connections for bulk data processing
    private static final int MAX_IDLE_CONNECTIONS = 100000;
    private static final long KEEP_ALIVE_DURATION_MINUTES = 5;
    
    // Dispatcher configuration for high throughput
    private static final int MAX_REQUESTS = 100000;
    private static final int MAX_REQUESTS_PER_HOST = 1000;
    
    // Timeout configuration (extended for bulk data)
    private static final long CONNECT_TIMEOUT_SECONDS = 5;
    private static final long READ_TIMEOUT_SECONDS = 30;
    private static final long WRITE_TIMEOUT_SECONDS = 30;
    
    // Thread pool for async operations
    private static final int THREAD_POOL_SIZE = 200;
    
    // Thread-safe singleton OkHttpClient
    private static volatile OkHttpClient sharedClient = null;
    
    /**
     * Get the shared OkHttpClient singleton.
     * Thread-safe, lazy initialization.
     * 
     * @return OkHttpClient instance
     */
    public static OkHttpClient getSharedClient() {
        if (sharedClient == null) {
            synchronized (OkHttpClientFactory.class) {
                if (sharedClient == null) {
                    sharedClient = createClient();
                }
            }
        }
        return sharedClient;
    }
    
    /**
     * Create a new OkHttpClient with optimized settings.
     * 
     * @return configured OkHttpClient
     */
    private static OkHttpClient createClient() {
        // Custom thread pool for high throughput
        java.util.concurrent.ExecutorService executorService = 
            java.util.concurrent.Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
                Thread t = new Thread(r, "OkHttp-Worker");
                t.setDaemon(true);
                return t;
            });
        
        // High-performance dispatcher
        Dispatcher dispatcher = new Dispatcher(executorService);
        dispatcher.setMaxRequests(MAX_REQUESTS);
        dispatcher.setMaxRequestsPerHost(MAX_REQUESTS_PER_HOST);
        
        // Create massive connection pool
        ConnectionPool connectionPool = new ConnectionPool(
            MAX_IDLE_CONNECTIONS,
            KEEP_ALIVE_DURATION_MINUTES,
            TimeUnit.MINUTES
        );
        
        // Build OkHttpClient with optimized settings
        return new OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // Disable built-in retry
            .followRedirects(true)
            .followSslRedirects(true)
            .build();
    }
    
    @Override
    public HttpClient newHttpClient() {
        // Returns Apache HttpClient wrapper for backward compatibility
        // The actual HTTP execution uses OkHttp internally via OkHttpHttpClient
        return new OkHttpHttpClient(getSharedClient());
    }
    
    /**
     * Get pool statistics for monitoring.
     * 
     * @return formatted statistics string
     */
    public static String getPoolStats() {
        OkHttpClient client = getSharedClient();
        int connectionCount = client.connectionPool().connectionCount();
        int idleConnectionCount = client.connectionPool().idleConnectionCount();
        Dispatcher dispatcher = client.dispatcher();
        
        return String.format(
            "OkHttp Pool[100k] - Connections: %d/%d idle, Requests: queued=%d running=%d/%d, Timeouts: C=%ds/R=%ds/W=%ds",
            idleConnectionCount,
            MAX_IDLE_CONNECTIONS,
            dispatcher.queuedCallsCount(),
            dispatcher.runningCallsCount(),
            MAX_REQUESTS,
            CONNECT_TIMEOUT_SECONDS,
            READ_TIMEOUT_SECONDS,
            WRITE_TIMEOUT_SECONDS
        );
    }
}