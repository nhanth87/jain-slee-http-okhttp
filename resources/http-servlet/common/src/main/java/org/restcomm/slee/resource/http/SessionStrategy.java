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

/**
 * Selects how the HTTP Servlet RA correlates incoming requests into a
 * multi-turn {@link net.java.slee.resource.http.HttpSessionActivity}.
 *
 * <p>The choice exists to support BOTH legacy RestComm Application Servers
 * that only send a {@code JSESSIONID} cookie and modern AS implementations
 * that carry a {@code X-Ussd-Session-Id} header (preferred for
 * observability and avoiding servlet container session pool overhead).
 *
 * <p>All three strategies share the same in-memory registry
 * ({@link org.restcomm.slee.resource.http.HttpSessionActivityRegistry})
 * so the underlying machinery is identical; only the resolution priority
 * differs.
 *
 * @author HYBRID strategy (SESS-1)
 */
public enum SessionStrategy {

    /**
     * Recommended for modern Application Servers.
     * 1) {@code X-Ussd-Session-Id} header
     * 2) {@code JSESSIONID} cookie
     * 3) null (caller falls back to localId / single-shot activity)
     */
    HEADER_FIRST,

    /**
     * Recommended for legacy RestComm AS.
     * 1) {@code JSESSIONID} cookie
     * 2) {@code X-Ussd-Session-Id} header
     * 3) null (caller falls back to localId / single-shot activity)
     */
    COOKIE_FIRST,

    /**
     * Legacy fallback — never read header or cookie. The session key is
     * always {@code null} so callers fall back to a generated localId.
     * Useful when a deployment is uncertain whether the AS sends
     * anything and operators want the original (pre-HYBRID) behaviour
     * with the SESS-1 risk.
     */
    LOCALID_ONLY;

    /**
     * Parses a string into a {@link SessionStrategy}, accepting
     * case-insensitive names and tolerating {@code null}/blank input by
     * defaulting to {@link #HEADER_FIRST}.
     *
     * @param value textual strategy name (e.g. "HEADER_FIRST")
     * @return the matching enum value, never {@code null}
     */
    public static SessionStrategy parse(String value) {
        if (value == null) {
            return HEADER_FIRST;
        }
        String s = value.trim();
        if (s.isEmpty()) {
            return HEADER_FIRST;
        }
        try {
            return SessionStrategy.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return HEADER_FIRST;
        }
    }
}