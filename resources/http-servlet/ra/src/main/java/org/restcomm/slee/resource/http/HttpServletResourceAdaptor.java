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

import java.lang.management.ManagementFactory;

import javax.management.ObjectName;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.slee.Address;
import javax.slee.facilities.EventLookupFacility;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ActivityAlreadyExistsException;
import javax.slee.resource.ActivityHandle;
import javax.slee.resource.ConfigProperties;
import javax.slee.resource.EventFlags;
import javax.slee.resource.FailureReason;
import javax.slee.resource.FireableEventType;
import javax.slee.resource.InvalidConfigurationException;
import javax.slee.resource.Marshaler;
import javax.slee.resource.ReceivableService;
import javax.slee.resource.ResourceAdaptor;
import javax.slee.resource.ResourceAdaptorContext;
import javax.slee.resource.SleeEndpoint;

import net.java.slee.resource.http.HttpSessionActivity;
import net.java.slee.resource.http.events.HttpServletRequestEvent;

import org.restcomm.slee.resource.http.events.HttpServletRequestEventImpl;
import org.restcomm.slee.resource.http.HttpSessionActivityRegistry;
import org.restcomm.slee.resource.http.SessionKeyResolver;
import org.restcomm.slee.resource.http.SessionStrategy;

/**
 *
 * @author martins
 *
 */
public class HttpServletResourceAdaptor implements ResourceAdaptor, HttpServletResourceEntryPoint {

    private static final String NAME_CONFIG_PROPERTY = "name";
    private static final String HTTP_REQUEST_TIMEOUT = "HTTP_REQUEST_TIMEOUT";
    /** HYBRID strategy (SESS-1). Default: header-first for modern AS. */
    private static final String HTTP_SESSION_STRATEGY = "HTTP_SESSION_STRATEGY";
    
    private ResourceAdaptorContext resourceAdaptorContext;
    private SleeEndpoint sleeEndpoint;
    private Tracer tracer;

    /**
     * the EventLookupFacility is used to look up the event id of incoming events
     */
    private EventLookupFacility eventLookup;

    private RequestLock requestLock;

    private HttpServletRaSbbInterfaceImpl httpRaSbbinterface;

    /**
     * caches the eventIDs, avoiding lookup in container
     */
    private EventIDCache eventIdCache;

    /**
     * tells the RA if an event with a specified ID should be filtered or not
     */
    private EventIDFilter eventIDFilter;

    /**
     * the ra entity name, which matches the servlet name
     */
    private String name;

    private Integer httpRequestTimeout;

    /**
     * HYBRID strategy (SESS-1). Volatile because it can be updated at
     * runtime via {@link javax.slee.resource.ResourceAdaptor#raConfigurationUpdate(ConfigProperties)}.
     */
    private volatile SessionStrategy sessionStrategy = SessionStrategy.HEADER_FIRST;

    /**
     *
     */
    public HttpServletResourceAdaptor() {
    }

    /**
     *
     * @return
     */
    public ResourceAdaptorContext getResourceAdaptorContext() {
        return resourceAdaptorContext;
    }

    /**
     *
     * @return
     */
    public SleeEndpoint getSleeEndpoint() {
        return sleeEndpoint;
    }

    /**
     *
     * @return
     */
    public String getName() {
        return name;
    }

    // lifecycle methods

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#setResourceAdaptorContext(javax.slee.resource.ResourceAdaptorContext)
     */
    public void setResourceAdaptorContext(ResourceAdaptorContext raContext) {
        this.resourceAdaptorContext = raContext;
        tracer = raContext.getTracer(HttpServletResourceAdaptor.class.getSimpleName());
        eventIdCache = new EventIDCache(raContext.getTracer(EventIDCache.class.getSimpleName()));
        eventIDFilter = new EventIDFilter();
        sleeEndpoint = raContext.getSleeEndpoint();
        eventLookup = raContext.getEventLookupFacility();
        requestLock = new RequestLock();
        httpRaSbbinterface = new HttpServletRaSbbInterfaceImpl(this);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#raConfigure(javax.slee.resource.ConfigProperties)
     */
    public void raConfigure(ConfigProperties configProperties) {
        name = (String) configProperties.getProperty(NAME_CONFIG_PROPERTY).getValue();
        configureUpdatableProperties(configProperties);
    }

    private void configureUpdatableProperties(ConfigProperties configProperties) {
    	httpRequestTimeout = (Integer) configProperties.getProperty(HTTP_REQUEST_TIMEOUT).getValue();
        // HYBRID strategy (SESS-1). Optional — defaults to HEADER_FIRST.
        ConfigProperties.Property strategyProp = configProperties.getProperty(HTTP_SESSION_STRATEGY);
        if (strategyProp != null && strategyProp.getValue() != null) {
            this.sessionStrategy = SessionStrategy.parse((String) strategyProp.getValue());
        } else if (tracer != null && tracer.isFineEnabled()) {
            tracer.fine("HTTP_SESSION_STRATEGY not configured, using default " + this.sessionStrategy);
        }
    }

    /**
     * Returns the currently-active session resolution strategy. Visible
     * for tests / diagnostics.
     */
    public SessionStrategy getSessionStrategy() {
        return this.sessionStrategy;
    }
    
    /*
    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#raActive()
     */
    public void raActive() {
        // register in manager
        HttpServletResourceEntryPointManager.putResourceEntryPoint(name, this);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#raStopping()
     */
    public void raStopping() {
    }

    /*
    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#raInactive()
     */
    public void raInactive() {
        // unregister from manager
        HttpServletResourceEntryPointManager.removeResourceEntryPoint(name);
    }

    /*
    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#raUnconfigure()
     */
    public void raUnconfigure() {
        name = null;
        httpRequestTimeout = null;
        sessionStrategy = SessionStrategy.HEADER_FIRST;
    }

    /*
    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#unsetResourceAdaptorContext()
     */
    public void unsetResourceAdaptorContext() {
        resourceAdaptorContext = null;
        tracer = null;
        eventIdCache = null;
        eventIDFilter = null;
        sleeEndpoint = null;
        eventLookup = null;
        requestLock = null;
        httpRaSbbinterface = null;
        sessionStrategy = SessionStrategy.HEADER_FIRST;
    }

    // config management methods

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#raVerifyConfiguration(javax.slee.resource.ConfigProperties)
     */
    public void raVerifyConfiguration(ConfigProperties configProperties) throws InvalidConfigurationException {
        ConfigProperties.Property property = configProperties.getProperty(NAME_CONFIG_PROPERTY);
        if (property == null) {
            throw new InvalidConfigurationException("name property not found");
        }
        if (!property.getType().equals(String.class.getName())) {
            throw new InvalidConfigurationException("name property must be of type java.lang.String");
        }
        if (property.getValue() == null) {
            // don't think this can happen, but just to be sure
            throw new InvalidConfigurationException("name property must not have a null value");
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#raConfigurationUpdate(javax.slee.resource.ConfigProperties)
     */
    public void raConfigurationUpdate(ConfigProperties configProperties) {
    	validateNameUpdate(configProperties);
    	configureUpdatableProperties(configProperties);
    }

    private void validateNameUpdate(ConfigProperties configProperties) throws UnsupportedOperationException {
    	String updateNameValue = (String) configProperties.getProperty(NAME_CONFIG_PROPERTY).getValue();
    	if(updateNameValue != null && !updateNameValue.equals(this.name))
    		throw new UnsupportedOperationException("name property update is not supported!");
    }
    
    // event filtering methods

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#serviceActive(javax.slee.resource.ReceivableService)
     */
    public void serviceActive(ReceivableService receivableService) {
        eventIDFilter.serviceActive(receivableService);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#serviceStopping(javax.slee.resource.ReceivableService)
     */
    public void serviceStopping(ReceivableService receivableService) {
        eventIDFilter.serviceStopping(receivableService);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#serviceInactive(javax.slee.resource.ReceivableService)
     */
    public void serviceInactive(ReceivableService receivableService) {
        eventIDFilter.serviceInactive(receivableService);
    }

    // mandatory callbacks

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#administrativeRemove(javax.slee.resource.ActivityHandle)
     */
    public void administrativeRemove(ActivityHandle activityHandle) {
        // nothing to do
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#getActivity(javax.slee.resource.ActivityHandle)
     */
    public Object getActivity(ActivityHandle activityHandle) {
        return activityHandle;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#getActivityHandle(java.lang.Object)
     */
    public javax.slee.resource.ActivityHandle getActivityHandle(Object object) {
        return (ActivityHandle) object;
    }

    // optional callbacks

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#activityEnded(javax.slee.resource.ActivityHandle)
     */
    public void activityEnded(ActivityHandle activityHandle) {
        // not used
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#activityUnreferenced(javax.slee.resource.ActivityHandle)
     */
    public void activityUnreferenced(ActivityHandle activityHandle) {
        // not used
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#eventProcessingFailed(javax.slee.resource.ActivityHandle,
     * javax.slee.resource.FireableEventType, java.lang.Object, javax.slee.Address,
	 * javax.slee.resource.ReceivableService, int, javax.slee.resource.FailureReason)
     */
    public void eventProcessingFailed(ActivityHandle activityHandle, FireableEventType fireableEventType,
            Object object, Address address, ReceivableService receivableService, int integer,
            FailureReason failureReason) {
        // not used
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#eventProcessingSuccessful(javax.slee.resource.ActivityHandle,
     * javax.slee.resource.FireableEventType, java.lang.Object, javax.slee.Address,
     * javax.slee.resource.ReceivableService, int)
     */
    public void eventProcessingSuccessful(ActivityHandle activityHandle, FireableEventType fireableEventType,
            Object object, Address address, ReceivableService receivableService, int integer) {
        // not used
    }

    /*
    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#eventUnreferenced(javax.slee.resource.ActivityHandle,
     * javax.slee.resource.FireableEventType, java.lang.Object, javax.slee.Address,
     * javax.slee.resource.ReceivableService, int)
     */
    public void eventUnreferenced(ActivityHandle arg0, FireableEventType fireableEventType,
            Object event, Address address, ReceivableService receivableService, int integer) {
        // release event thread
        releaseHttpRequest((HttpServletRequestEvent) event);
    }

    /**
     * Allows control to be returned back to the servlet conainer, which delivered the http request. The container will
     * mandatory close the response stream.
     *
     */
    private void releaseHttpRequest(HttpServletRequestEvent hreqEvent) {
        if (tracer.isFinestEnabled()) {
            tracer.finest("releaseHttpRequest() enter");
        }

        final Object lock = requestLock.removeLock(hreqEvent);
        if (lock != null) {
            synchronized (lock) {
                lock.notify();
            }
        }

        if (tracer.isFineEnabled()) {
            tracer.fine("released lock for http request " + hreqEvent.getId());
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#queryLiveness(javax.slee.resource.ActivityHandle)
     */
    public void queryLiveness(javax.slee.resource.ActivityHandle activityHandle) {
        // end any idle activity, it should be a leak, this is true assuming
        // that jboss web session timeout is smaller than the container timeout
        // to invoke this method
        if (tracer.isInfoEnabled()) {
            tracer.info("Activity " + activityHandle
                    + " is idle in the container, terminating.");
        }
        endActivity((AbstractHttpServletActivity) activityHandle);
    }

    // interface accessors

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#getResourceAdaptorInterface(java.lang.String)
     */
    public Object getResourceAdaptorInterface(String arg0) {
        return httpRaSbbinterface;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.slee.resource.ResourceAdaptor#getMarshaler()
     */
    public Marshaler getMarshaler() {
        return null;
    }

    // ra logic

    /*
     * (non-Javadoc)
     * 
     * @see HttpServletResourceEntryPoint#onRequest
	 * (javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public void onRequest(HttpServletRequest request, HttpServletResponse response) {
        AbstractHttpServletActivity activity = null;
        HttpSessionActivity existingSessionActivity = null;
        String resolvedKey = null;

        final HttpServletRequestWrapper requestWrapper = new HttpServletRequestWrapper(request);

        // HYBRID strategy (SESS-1):
        //   Resolve a session key from the request WITHOUT touching the
        //   servlet container's session pool. The wrapper still exists
        //   for HttpServletRequestEventImpl compatibility, but we read
        //   only headers/cookies here.
        final SessionStrategy currentStrategy = this.sessionStrategy;
        if (currentStrategy != SessionStrategy.LOCALID_ONLY) {
            resolvedKey = SessionKeyResolver.resolve(requestWrapper, currentStrategy);
        }
        if (tracer.isFineEnabled()) {
            tracer.fine("onRequest: strategy=" + currentStrategy + " resolvedKey=" + resolvedKey);
        }
        if (resolvedKey != null) {
            existingSessionActivity = HttpSessionActivityRegistry.getInstance().lookup(resolvedKey);
        }

        // For event-name resolution we still need to know whether the
        // request is multi-turn. Build a "synthetic" HttpSession marker:
        // the legacy code passed the wrapper directly. We pass null when
        // there is no key (single-shot path) and rely on the registry to
        // decide whether this is multi-turn.
        final HttpSessionWrapper sessionWrapper = null;
        final HttpServletRequestEvent requestEvent = new HttpServletRequestEventImpl(requestWrapper, response, this);
        final FireableEventType eventType = eventIdCache.getEventType(eventLookup, requestEvent, sessionWrapper);

        response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);

        if (eventIDFilter.filterEvent(eventType)) {
            if (tracer.isInfoEnabled()) {
                tracer.info("Request event filtered: " + requestEvent);
            }
            // dude, get out of here
            return;
        }

        boolean createActivity = true;
        if (existingSessionActivity != null) {
            // Reuse existing multi-turn activity from the registry.
            activity = (AbstractHttpServletActivity) existingSessionActivity;
            createActivity = false;
        } else if (resolvedKey != null) {
            // Create new multi-turn activity with explicit key (HYBRID).
            // No servlet session involved — the key is either a header
            // value or a JSESSIONID cookie value, decided by the strategy.
            activity = new HttpSessionActivityImpl(resolvedKey);
        } else {
            // No session key at all — single-shot request activity.
            activity = new HttpServletRequestActivityImpl();
        }

        if (createActivity) {
            try {
                sleeEndpoint.startActivity(activity, activity);
                // After successful startActivity, register multi-turn
                // activities under their key so subsequent requests with
                // the same key correlate to the same activity.
                if (resolvedKey != null && activity instanceof HttpSessionActivity) {
                    HttpSessionActivityRegistry.getInstance().register(resolvedKey, (HttpSessionActivity) activity);
                }
            } catch (ActivityAlreadyExistsException e) {
                if (tracer.isFineEnabled()) {
                    tracer.fine("Failed to add activity " + activity, e);
                }
                // proceed, may be due to fail over
            } catch (Throwable e) {
                tracer.severe("Failed to add activity " + activity, e);
                return;
            }
        }

        if (tracer.isFineEnabled()) {
            tracer.fine("Firing event " + requestEvent + " in activity " + activity
                    + " (strategy=" + currentStrategy + ", key=" + resolvedKey + ")");
        }

        final Object lock = requestLock.getLock(requestEvent);
        synchronized (lock) {
            try {
                sleeEndpoint.fireEvent(activity, eventType, requestEvent, null, null, EventFlags.REQUEST_EVENT_UNREFERENCED_CALLBACK);
                // block thread until event has been processed
                lock.wait(httpRequestTimeout);
                // Single-shot path: end the activity after the event was
                // processed. Multi-turn activities are ended explicitly
                // by the SBB (e.g. via HttpSessionActivity.endActivity).
                if (existingSessionActivity == null && resolvedKey == null) {
                    endActivity(activity);
                }
            } catch (Throwable e) {
                tracer.severe("Failure while firing event " + requestEvent + " on activity " + activity, e);
            }
        }
    }

    private void endActivity(AbstractHttpServletActivity activity) {
        if (tracer.isInfoEnabled()) {
            tracer.fine("Ending activity " + activity);
        }
        try {
            sleeEndpoint.endActivity(activity);
        } catch (Throwable e) {
            tracer.severe("Failed to end activity " + activity, e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see HttpServletResourceEntryPoint#onSessionTerminated(java.lang.String)
     */
    public void onSessionTerminated(HttpSessionWrapper httpSessionWrapper) {
        endActivity(new HttpSessionActivityImpl(httpSessionWrapper));
    }
    
    private String getJBossAddress() {
		String address = null;
		Object inetAddress = null;
		try {
			inetAddress = ManagementFactory.getPlatformMBeanServer()
					.getAttribute(new ObjectName("jboss.as:interface=public"), "inet-address");			
		} catch (Exception e) {
		}

		if (inetAddress != null) {
			address = inetAddress.toString();
		}

		return address;
	}
    
    private String getJBossPort() {
		String port = null;
		Object httpPort = null;
		try {
			httpPort = ManagementFactory.getPlatformMBeanServer()
					.getAttribute(new ObjectName("jboss.as:socket-binding-group=standard-sockets,socket-binding=http"), "port");			
		} catch (Exception e) {
		}

		if (httpPort != null) {
			port = httpPort.toString();
		}

		return port;
	}
}
