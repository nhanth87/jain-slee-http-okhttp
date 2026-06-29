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

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

/**
 * Pure-function helper that decides which key (header value or cookie
 * value) should be used to correlate an inbound HTTP request with an
 * existing {@link net.java.slee.resource.http.HttpSessionActivity}.
 *
 * <p>This class deliberately does NOT call
 * {@link HttpServletRequest#getSession(boolean)} — doing so would touch
 * the servlet container's session pool and defeat the purpose of the
 * HYBRID strategy. We only inspect request headers and cookies, which
 * are always present regardless of whether a servlet session has been
 * created.
 *
 * <p>The resolver is stateless and safe for concurrent use from
 * multiple RA threads.
 *
 * @author HYBRID strategy (SESS-1)
 */
public final class SessionKeyResolver {

    /** HTTP header carrying the modern USSD session id (preferred). */
    public static final String HEADER_SESSION_ID = "X-Ussd-Session-Id";

    /** Cookie name carrying the legacy servlet container session id. */
    public static final String COOKIE_NAME = "JSESSIONID";

    /** Private ctor — utility class. */
    private SessionKeyResolver() {
        // no instances
    }

    /**
     * Resolves a session key from an inbound request using the supplied
     * strategy. Returns {@code null} when no usable key is found.
     *
     * <p>Reads only request headers and cookies; never invokes
     * {@code request.getSession()}.
     */
    public static String resolve(HttpServletRequest request, SessionStrategy strategy) {
        if (request == null || strategy == null) {
            return null;
        }
        final String headerValue = request.getHeader(HEADER_SESSION_ID);
        final Cookie[] cookies = request.getCookies();
        return resolveFromParts(headerValue, cookies, strategy);
    }

    /**
     * Testable pure function. Given the already-parsed header value and
     * cookie array, applies the strategy's priority order and returns
     * the resolved session key, or {@code null} if none.
     */
    public static String resolveFromParts(String headerValue, Cookie[] cookies, SessionStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        switch (strategy) {
            case LOCALID_ONLY:
                return null;
            case HEADER_FIRST: {
                final String fromHeader = normalize(headerValue);
                if (fromHeader != null) {
                    return fromHeader;
                }
                return findJSessionId(cookies);
            }
            case COOKIE_FIRST: {
                final String fromCookie = findJSessionId(cookies);
                if (fromCookie != null) {
                    return fromCookie;
                }
                return normalize(headerValue);
            }
            default:
                return null;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String findJSessionId(Cookie[] cookies) {
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (c != null && COOKIE_NAME.equals(c.getName())) {
                return normalize(c.getValue());
            }
        }
        return null;
    }
}