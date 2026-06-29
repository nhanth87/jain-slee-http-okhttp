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

import net.java.slee.resource.http.HttpSessionActivity;

/**
 *
 * @author amit.bhayani
 * @author martins
 *
 */
public class HttpSessionActivityImpl extends AbstractHttpServletActivity implements HttpSessionActivity {

    /**
     * Legacy servlet-container session wrapper. {@code null} when this
     * activity was created by the HYBRID strategy with an explicit
     * String key (no servlet session involved).
     */
    private final HttpSessionWrapper httpSessionWrapper;

	/**
	 *
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * Legacy constructor used by the
	 * {@link HttpServletRaSbbInterfaceImpl#getHttpSessionActivity(HttpSession)}
	 * path. The wrapper is used both as the source of the session id
	 * AND as the handle to invalidate when the activity ends.
	 */
	public HttpSessionActivityImpl(HttpSessionWrapper httpSessionWrapper) {
        super(httpSessionWrapper.getId());
        this.httpSessionWrapper = httpSessionWrapper;
    }

	/**
	 * HYBRID strategy constructor (SESS-1). Builds an activity around
	 * an explicit session key resolved by {@link SessionKeyResolver}
	 * (either a {@code X-Ussd-Session-Id} header value or a
	 * {@code JSESSIONID} cookie value). No servlet container session
	 * is involved — the activity is tracked solely via
	 * {@link HttpSessionActivityRegistry}.
	 *
	 * @param sessionKey the explicit session key, must not be {@code null}
	 */
	public HttpSessionActivityImpl(String sessionKey) {
        super(sessionKey);
        this.httpSessionWrapper = null;
    }


	public String getSessionId() {
		return id;
	}


    @Override
    public void endActivity() {
        // Always remove from the registry first so concurrent lookups
        // by the same key immediately stop finding this activity.
        try {
            HttpSessionActivityRegistry.getInstance().remove(id);
        } catch (Throwable ignore) {
            // registry must not block endActivity; ignore
        }
        // Legacy path: also invalidate the underlying servlet session
        // if one was attached.
        if (httpSessionWrapper != null) {
            try {
                this.httpSessionWrapper.invalidate();
            } catch (Throwable ignore) {
                // already invalidated by container — ignore
            }
        }
    }

}
