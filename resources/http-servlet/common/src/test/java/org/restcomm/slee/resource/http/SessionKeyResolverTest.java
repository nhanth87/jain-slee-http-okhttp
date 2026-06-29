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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import javax.servlet.http.Cookie;

import org.junit.Test;

/**
 * Tests for the pure {@link SessionKeyResolver}.
 *
 * <p>Covers all three {@link SessionStrategy} modes plus edge cases
 * (empty values, multiple cookies, whitespace trimming).
 */
public class SessionKeyResolverTest {

    private static final String HEADER_VALUE = "ussd-session-123";
    private static final String COOKIE_VALUE = "JSESSION-A1B2C3";

    private static Cookie[] cookies(String name, String value) {
        return new Cookie[] { new Cookie(name, value) };
    }

    private static Cookie[] multiCookies() {
        return new Cookie[] {
                new Cookie("other", "ignore-me"),
                new Cookie(SessionKeyResolver.COOKIE_NAME, COOKIE_VALUE),
                new Cookie("another", "ignore-me-too")
        };
    }

    // ---- HEADER_FIRST ----

    @Test
    public void headerFirstHeaderPresentReturnsHeader() {
        assertEquals(HEADER_VALUE,
                SessionKeyResolver.resolveFromParts(HEADER_VALUE, null, SessionStrategy.HEADER_FIRST));
    }

    @Test
    public void headerFirstHeaderAbsentCookiePresentReturnsCookie() {
        assertEquals(COOKIE_VALUE,
                SessionKeyResolver.resolveFromParts(null, cookies(SessionKeyResolver.COOKIE_NAME, COOKIE_VALUE),
                        SessionStrategy.HEADER_FIRST));
    }

    @Test
    public void headerFirstBothAbsentReturnsNull() {
        assertNull(SessionKeyResolver.resolveFromParts(null, null, SessionStrategy.HEADER_FIRST));
    }

    @Test
    public void headerFirstEmptyHeaderFallsThroughToCookie() {
        assertEquals(COOKIE_VALUE,
                SessionKeyResolver.resolveFromParts("", cookies(SessionKeyResolver.COOKIE_NAME, COOKIE_VALUE),
                        SessionStrategy.HEADER_FIRST));
    }

    @Test
    public void headerFirstWhitespaceHeaderFallsThroughToCookie() {
        assertEquals(COOKIE_VALUE,
                SessionKeyResolver.resolveFromParts("   ", cookies(SessionKeyResolver.COOKIE_NAME, COOKIE_VALUE),
                        SessionStrategy.HEADER_FIRST));
    }

    @Test
    public void headerFirstHeaderTrimsWhitespace() {
        assertEquals(HEADER_VALUE,
                SessionKeyResolver.resolveFromParts("  " + HEADER_VALUE + "  ", null, SessionStrategy.HEADER_FIRST));
    }

    // ---- COOKIE_FIRST ----

    @Test
    public void cookieFirstCookiePresentReturnsCookie() {
        assertEquals(COOKIE_VALUE,
                SessionKeyResolver.resolveFromParts(null, cookies(SessionKeyResolver.COOKIE_NAME, COOKIE_VALUE),
                        SessionStrategy.COOKIE_FIRST));
    }

    @Test
    public void cookieFirstCookieAbsentHeaderPresentReturnsHeader() {
        assertEquals(HEADER_VALUE,
                SessionKeyResolver.resolveFromParts(HEADER_VALUE, null, SessionStrategy.COOKIE_FIRST));
    }

    @Test
    public void cookieFirstBothAbsentReturnsNull() {
        assertNull(SessionKeyResolver.resolveFromParts(null, null, SessionStrategy.COOKIE_FIRST));
    }

    @Test
    public void cookieFirstHeaderPrecedenceOverEmptyHeader() {
        assertNull(SessionKeyResolver.resolveFromParts("", null, SessionStrategy.COOKIE_FIRST));
    }

    // ---- LOCALID_ONLY ----

    @Test
    public void localidOnlyAlwaysReturnsNullEvenWithHeader() {
        assertNull(SessionKeyResolver.resolveFromParts(HEADER_VALUE, null, SessionStrategy.LOCALID_ONLY));
    }

    @Test
    public void localidOnlyAlwaysReturnsNullEvenWithCookie() {
        assertNull(SessionKeyResolver.resolveFromParts(null, cookies(SessionKeyResolver.COOKIE_NAME, COOKIE_VALUE),
                SessionStrategy.LOCALID_ONLY));
    }

    @Test
    public void localidOnlyAlwaysReturnsNullWithBoth() {
        assertNull(SessionKeyResolver.resolveFromParts(HEADER_VALUE,
                cookies(SessionKeyResolver.COOKIE_NAME, COOKIE_VALUE), SessionStrategy.LOCALID_ONLY));
    }

    // ---- Cookie selection edge cases ----

    @Test
    public void findsJSessionIdAmongMultipleCookies() {
        assertEquals(COOKIE_VALUE,
                SessionKeyResolver.resolveFromParts(null, multiCookies(), SessionStrategy.COOKIE_FIRST));
    }

    @Test
    public void doesNotMatchLowercaseJSessionId() {
        Cookie[] cs = cookies("jsessionid", COOKIE_VALUE);
        assertNull("lowercase jsessionid must not match",
                SessionKeyResolver.resolveFromParts(null, cs, SessionStrategy.COOKIE_FIRST));
        assertNull("lowercase jsessionid must not match (HEADER_FIRST either)",
                SessionKeyResolver.resolveFromParts(null, cs, SessionStrategy.HEADER_FIRST));
    }

    @Test
    public void emptyCookieArrayReturnsNull() {
        assertNull(SessionKeyResolver.resolveFromParts(null, new Cookie[0], SessionStrategy.COOKIE_FIRST));
    }

    @Test
    public void nullCookieEntryIsSkipped() {
        Cookie[] cs = new Cookie[] { null, new Cookie(SessionKeyResolver.COOKIE_NAME, COOKIE_VALUE) };
        assertEquals(COOKIE_VALUE,
                SessionKeyResolver.resolveFromParts(null, cs, SessionStrategy.COOKIE_FIRST));
    }

    @Test
    public void blankCookieValueTreatedAsAbsent() {
        Cookie[] cs = cookies(SessionKeyResolver.COOKIE_NAME, "   ");
        assertNull(SessionKeyResolver.resolveFromParts(null, cs, SessionStrategy.COOKIE_FIRST));
    }

    // ---- Strategy parsing ----

    @Test
    public void strategyParseHandlesNullAndBlank() {
        assertEquals(SessionStrategy.HEADER_FIRST, SessionStrategy.parse(null));
        assertEquals(SessionStrategy.HEADER_FIRST, SessionStrategy.parse(""));
        assertEquals(SessionStrategy.HEADER_FIRST, SessionStrategy.parse("   "));
    }

    @Test
    public void strategyParseCaseInsensitive() {
        assertEquals(SessionStrategy.HEADER_FIRST, SessionStrategy.parse("header_first"));
        assertEquals(SessionStrategy.COOKIE_FIRST, SessionStrategy.parse("COOKIE_first"));
        assertEquals(SessionStrategy.LOCALID_ONLY, SessionStrategy.parse("localid_only"));
    }

    @Test
    public void strategyParseInvalidFallsBackToDefault() {
        assertEquals(SessionStrategy.HEADER_FIRST, SessionStrategy.parse("not-a-real-strategy"));
    }

    // ---- Null request handling (defensive) ----

    @Test
    public void resolveNullRequestReturnsNull() {
        assertNull(SessionKeyResolver.resolve(null, SessionStrategy.HEADER_FIRST));
    }

    @Test
    public void resolveNullStrategyReturnsNull() {
        assertNull(SessionKeyResolver.resolveFromParts(HEADER_VALUE, null, null));
    }

    // ---- Constants sanity ----

    @Test
    public void constantsAreStable() {
        assertNotNull(SessionKeyResolver.COOKIE_NAME);
        assertNotNull(SessionKeyResolver.HEADER_SESSION_ID);
        assertEquals("JSESSIONID", SessionKeyResolver.COOKIE_NAME);
        assertEquals("X-Ussd-Session-Id", SessionKeyResolver.HEADER_SESSION_ID);
    }
}
