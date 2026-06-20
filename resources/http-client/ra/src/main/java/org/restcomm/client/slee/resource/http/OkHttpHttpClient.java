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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * <p>
 * Apache HttpClient wrapper around OkHttp for backward compatibility.
 * </p>
 * 
 * <p>
 * This class adapts OkHttp's API to Apache HttpClient interface,
 * allowing the HTTP RA to use OkHttp with 100k connection pool
 * while maintaining the existing SLEE integration.
 * </p>
 * 
 * @author Matrix Agent (Optimization)
 */
public class OkHttpHttpClient implements HttpClient {

    private final OkHttpClient okHttpClient;
    
    public OkHttpHttpClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }
    
    @Override
    public HttpResponse execute(HttpUriRequest request) throws IOException {
        // Convert Apache HttpUriRequest to OkHttp Request
        Request okRequest = convertRequest(request);
        
        // Execute synchronously
        try (Response response = okHttpClient.newCall(okRequest).execute()) {
            return convertResponse(response);
        }
    }
    
    @Override
    public HttpResponse execute(HttpHost target, HttpRequest request) throws IOException {
        // Convert Apache request to OkHttp Request
        Request okRequest = convertRequest(request, target);
        
        // Execute synchronously
        try (Response response = okHttpClient.newCall(okRequest).execute()) {
            return convertResponse(response);
        }
    }
    
    @Override
    public HttpResponse execute(HttpHost target, HttpRequest request, HttpContext context) throws IOException {
        // Ignore context for now, same as above
        return execute(target, request);
    }
    
    @Override
    public HttpResponse execute(HttpUriRequest request, HttpContext context) throws IOException {
        return execute(request);
    }
    
    @Override
    public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> responseHandler) throws IOException {
        HttpResponse response = execute(request);
        return responseHandler.handleResponse(response);
    }
    
    @Override
    public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context) throws IOException {
        HttpResponse response = execute(request, context);
        return responseHandler.handleResponse(response);
    }
    
    @Override
    public <T> T execute(HttpHost target, HttpRequest request, ResponseHandler<? extends T> responseHandler) throws IOException {
        HttpResponse response = execute(target, request);
        return responseHandler.handleResponse(response);
    }
    
    @Override
    public <T> T execute(HttpHost target, HttpRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context) throws IOException {
        HttpResponse response = execute(target, request, context);
        return responseHandler.handleResponse(response);
    }
    
    /**
     * Execute request asynchronously with callback.
     * Used by HTTP RA for async operations.
     * 
     * @param request the Apache HttpUriRequest
     * @param context the HTTP context
     * @param callback callback for completion
     */
    public void executeAsync(HttpUriRequest request, HttpContext context, ApacheHttpResponseCallback callback) {
        Request okRequest = convertRequest(request);
        
        okHttpClient.newCall(okRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onFailure(e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                HttpResponse httpResponse = convertResponse(response);
                callback.onResponse(httpResponse);
            }
        });
    }
    
    @Override
    public ClientConnectionManager getConnectionManager() {
        // Not used - OkHttp manages its own connection pool
        return null;
    }
    
    @Override
    public HttpParams getParams() {
        // Not used - OkHttp uses builder pattern
        return null;
    }
    
    /**
     * Convert Apache HttpUriRequest to OkHttp Request.
     */
    private Request convertRequest(HttpUriRequest request) {
        Request.Builder builder = new Request.Builder()
            .url(request.getURI().toString());
        
        // Add headers
        org.apache.http.Header[] headers = request.getAllHeaders();
        if (headers != null) {
            for (org.apache.http.Header header : headers) {
                builder.addHeader(header.getName(), header.getValue());
            }
        }
        
        // Set method and body
        String method = request.getMethod();
        if (request instanceof org.apache.http.HttpEntityEnclosingRequest) {
            org.apache.http.HttpEntity entity = ((org.apache.http.HttpEntityEnclosingRequest) request).getEntity();
            if (entity != null) {
                try {
                    byte[] bodyBytes = entityToBytes(entity);
                    builder.method(method, okhttp3.RequestBody.create(getMediaType(entity.getContentType()), bodyBytes));
                } catch (IOException e) {
                    builder.method(method, null);
                }
            } else {
                builder.method(method, null);
            }
        } else {
            builder.method(method, null);
        }
        
        return builder.build();
    }
    
    /**
     * Convert Apache HttpRequest to OkHttp Request with target host.
     */
    private Request convertRequest(HttpRequest request, HttpHost target) {
        String url;
        if (request instanceof HttpUriRequest) {
            url = ((HttpUriRequest) request).getURI().toString();
        } else {
            // Construct URL from target
            url = target.toURI();
        }
        
        Request.Builder builder = new Request.Builder().url(url);
        
        // Add headers
        org.apache.http.Header[] headers = request.getAllHeaders();
        if (headers != null) {
            for (org.apache.http.Header header : headers) {
                builder.addHeader(header.getName(), header.getValue());
            }
        }
        
        return builder.build();
    }
    
    /**
     * Convert OkHttp Response to Apache HttpResponse.
     */
    private HttpResponse convertResponse(Response response) throws IOException {
        return new OkHttpResponse(response);
    }
    
    /**
     * Convert entity content to bytes using Okio for efficient buffering.
     * Okio is bundled with OkHttp and provides optimized I/O operations.
     */
    private byte[] entityToBytes(org.apache.http.HttpEntity entity) throws IOException {
        try (InputStream is = entity.getContent()) {
            // Use Okio for efficient byte array extraction
            // Okio uses segmented buffers (like a linked list of 8KB segments)
            // which reduces memory copying compared to ByteArrayOutputStream
            okio.Buffer buffer = new okio.Buffer();
            buffer.readFrom(is);
            return buffer.readByteArray();
        }
    }
    
    /**
     * Get OkHttp MediaType from Apache ContentType.
     */
    private okhttp3.MediaType getMediaType(org.apache.http.Header contentTypeHeader) {
        if (contentTypeHeader != null) {
            return okhttp3.MediaType.parse(contentTypeHeader.getValue());
        }
        return okhttp3.MediaType.parse("application/octet-stream");
    }
    
    /**
     * Callback interface for async HTTP responses.
     */
    public interface ApacheHttpResponseCallback {
        void onResponse(HttpResponse response);
        void onFailure(IOException e);
    }
    
    /**
     * OkHttp Response adapter to Apache HttpResponse interface.
     */
    private static class OkHttpResponse implements HttpResponse {
        
        private final Response response;
        private final int statusCode;
        private final String reasonPhrase;
        private org.apache.http.params.BasicHttpParams params;
        
        public OkHttpResponse(Response response) {
            this.response = response;
            this.statusCode = response.code();
            this.reasonPhrase = response.message();
        }
        
        @Override
        public org.apache.http.ProtocolVersion getProtocolVersion() {
            return response.protocol() == okhttp3.Protocol.HTTP_1_1
                ? org.apache.http.HttpVersion.HTTP_1_1
                : org.apache.http.HttpVersion.HTTP_1_0;
        }
        
        @Override
        public org.apache.http.StatusLine getStatusLine() {
            return new org.apache.http.StatusLine() {
                @Override
                public org.apache.http.ProtocolVersion getProtocolVersion() {
                    return response.protocol() == okhttp3.Protocol.HTTP_1_1 
                        ? org.apache.http.HttpVersion.HTTP_1_1 
                        : org.apache.http.HttpVersion.HTTP_1_0;
                }
                
                @Override
                public int getStatusCode() {
                    return statusCode;
                }
                
                @Override
                public String getReasonPhrase() {
                    return reasonPhrase;
                }
            };
        }
        
        @Override
        public void setStatusLine(org.apache.http.StatusLine statusline) {
            // Not used
        }
        
        @Override
        public void setStatusLine(org.apache.http.ProtocolVersion proto, int code) {
            // Not used
        }
        
        @Override
        public void setStatusLine(org.apache.http.ProtocolVersion proto, int code, String reason) {
            // Not used
        }
        
        @Override
        public void setStatusCode(int code) throws IllegalStateException {
            throw new IllegalStateException("Read-only");
        }
        
        @Override
        public void setReasonPhrase(String reason) throws IllegalStateException {
            throw new IllegalStateException("Read-only");
        }
        
        @Override
        public org.apache.http.HttpEntity getEntity() {
            return new OkHttpEntity(response.body());
        }
        
        @Override
        public void setEntity(org.apache.http.HttpEntity entity) {
            // Not used
        }
        
        @Override
        public Locale getLocale() {
            return java.util.Locale.getDefault();
        }
        
        @Override
        public void setLocale(Locale loc) {
            // Not used
        }
        
        @Override
        public org.apache.http.params.HttpParams getParams() {
            if (params == null) {
                params = new org.apache.http.params.BasicHttpParams();
            }
            return params;
        }
        
        @Override
        public void setParams(org.apache.http.params.HttpParams params) {
            // Not used
        }
        
        @Override
        public void addHeader(org.apache.http.Header header) {
            // Not used
        }
        
        @Override
        public void addHeader(String name, String value) {
            // Not used
        }
        
        @Override
        public void setHeader(org.apache.http.Header header) {
            // Not used
        }
        
        @Override
        public void setHeader(String name, String value) {
            // Not used
        }
        
        @Override
        public void setHeaders(org.apache.http.Header[] headers) {
            // Not used
        }
        
        @Override
        public org.apache.http.Header getFirstHeader(String name) {
            String okHeader = response.header(name);
            if (okHeader != null) {
                return new org.apache.http.message.BasicHeader(name, okHeader);
            }
            return null;
        }
        
        @Override
        public org.apache.http.Header getLastHeader(String name) {
            return getFirstHeader(name);
        }
        
        @Override
        public org.apache.http.Header[] getHeaders(String name) {
            String okHeader = response.header(name);
            if (okHeader != null) {
                return new org.apache.http.Header[] { new org.apache.http.message.BasicHeader(name, okHeader) };
            }
            return new org.apache.http.Header[0];
        }
        
        @Override
        public org.apache.http.Header[] getAllHeaders() {
            return response.headers().names().stream()
                .map(name -> new org.apache.http.message.BasicHeader(name, response.header(name)))
                .toArray(org.apache.http.Header[]::new);
        }
        
        @Override
        public boolean containsHeader(String name) {
            return response.header(name) != null;
        }
        
        @Override
        public void removeHeader(org.apache.http.Header header) {
            // Not used
        }
        
        @Override
        public void removeHeaders(String name) {
            // Not used
        }
        
        @Override
        public org.apache.http.HeaderIterator headerIterator() {
            return new org.apache.http.message.BasicHeaderIterator(getAllHeaders(), null);
        }
        
        @Override
        public org.apache.http.HeaderIterator headerIterator(String name) {
            return new org.apache.http.message.BasicHeaderIterator(getAllHeaders(), name);
        }
    }
    
    /**
     * OkHttp ResponseBody adapter to Apache HttpEntity.
     */
    private static class OkHttpEntity implements org.apache.http.HttpEntity {
        
        private final ResponseBody body;
        
        public OkHttpEntity(ResponseBody body) {
            this.body = body;
        }
        
        @Override
        public boolean isRepeatable() {
            return false;
        }
        
        @Override
        public boolean isChunked() {
            return false;
        }
        
        @Override
        public boolean isStreaming() {
            return true;
        }
        
        @Override
        public long getContentLength() {
            return body.contentLength();
        }
        
        @Override
        public org.apache.http.Header getContentType() {
            if (body.contentType() != null) {
                return new org.apache.http.message.BasicHeader("Content-Type", body.contentType().toString());
            }
            return null;
        }
        
        @Override
        public org.apache.http.Header getContentEncoding() {
            return null;
        }
        
        @Override
        public InputStream getContent() throws IOException {
            return body.byteStream();
        }
        
        @Override
        public void writeTo(OutputStream out) throws IOException {
            // Use Okio for efficient streaming with segmented buffers
            // Okio's buffer uses a pool of 8KB segments, reducing GC pressure
            // and memory copies compared to a single large byte array
            try (InputStream in = body.byteStream()) {
                okio.BufferedSource source = okio.Okio.buffer(okio.Okio.source(in));
                okio.Buffer buffer = new okio.Buffer();
                long bytesRead;
                // Read in 8KB chunks using Okio's efficient buffer
                while ((bytesRead = source.read(buffer, 8192)) != -1) {
                    if (bytesRead > 0) {
                        buffer.writeTo(out);
                    }
                }
                out.flush();
            }
        }
        
        @Override
        public void consumeContent() throws IOException {
            // Not needed for streaming
        }
    }
}