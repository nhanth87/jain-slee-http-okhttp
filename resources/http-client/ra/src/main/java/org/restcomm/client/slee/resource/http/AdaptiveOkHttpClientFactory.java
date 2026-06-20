package org.restcomm.client.slee.resource.http;

import java.util.concurrent.TimeUnit;

import org.apache.http.client.HttpClient;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * Adaptive fair-share OkHttp factory for dynamic USSD outbound URLs @ 10K TPS.
 *
 * <p>Configure RA with {@code HTTP_CLIENT_FACTORY=org.restcomm.client.slee.resource.http.AdaptiveOkHttpClientFactory}
 * and global pool properties only (no per-route list).</p>
 */
public class AdaptiveOkHttpClientFactory implements HttpClientFactory {

    private static final int OKHTTP_MAX_REQUESTS = 10000;
    private static final int OKHTTP_IDLE_CONNECTIONS = 512;
    private static final long KEEP_ALIVE_MINUTES = 5;
    private static final long CONNECT_TIMEOUT_SECONDS = 5;
    private static final long READ_TIMEOUT_SECONDS = 30;
    private static final long WRITE_TIMEOUT_SECONDS = 30;
    private static final int DISPATCHER_THREADS = 128;

    private static volatile OkHttpClient sharedClient;

    @Override
    public HttpClient newHttpClient() {
        return new OkHttpHttpClient(getSharedClient());
    }

    public static OkHttpClient getSharedClient() {
        if (sharedClient == null) {
            synchronized (AdaptiveOkHttpClientFactory.class) {
                if (sharedClient == null) {
                    sharedClient = buildClient();
                }
            }
        }
        return sharedClient;
    }

    private static OkHttpClient buildClient() {
        AdaptiveHttpClientConfig config = AdaptiveHttpClientConfig.get();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(
                DISPATCHER_THREADS, r -> {
                    Thread t = new Thread(r, "AdaptiveOkHttp-Worker");
                    t.setDaemon(true);
                    return t;
                });

        Dispatcher dispatcher = new Dispatcher(executor);
        dispatcher.setMaxRequests(OKHTTP_MAX_REQUESTS);
        dispatcher.setMaxRequestsPerHost(config.getMaxPerHost());

        ConnectionPool pool = new ConnectionPool(OKHTTP_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES);

        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(pool)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    public static String getPoolStats() {
        OkHttpClient client = getSharedClient();
        Dispatcher dispatcher = client.dispatcher();
        return String.format(
                "AdaptiveOkHttp[maxTotal=%d] connections idle=%d/%d queued=%d running=%d/%d",
                AdaptiveHttpClientConfig.get().getMaxTotal(),
                client.connectionPool().idleConnectionCount(),
                OKHTTP_IDLE_CONNECTIONS,
                dispatcher.queuedCallsCount(),
                dispatcher.runningCallsCount(),
                OKHTTP_MAX_REQUESTS);
    }
}
