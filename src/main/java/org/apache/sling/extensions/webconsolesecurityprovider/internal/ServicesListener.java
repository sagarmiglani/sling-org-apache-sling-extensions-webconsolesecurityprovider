package org.apache.sling.extensions.webconsolesecurityprovider.internal;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.Dictionary;
import java.util.Hashtable;

import org.apache.felix.webconsole.WebConsoleSecurityProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>ServicesListener</code> listens for the required services
 * and registers the security provider when required services are available.
 *
 * It supports 3 modes, which can be forced by the value of the framework property "sling.webconsole.authType"
 * <ul>
 *   <li> "jcrAuth": always authenticate against the JCR repository even if Sling Authentication is possible.</li>
 *   <li> "slingAuth": always use SlingAuthentication
 *   <li> no value (default) : Use SlingAuthentication if available, fallback to JCR repository
 *   <li> If an invalid value is specifed, the value is ignored and the default is used
 * </ul>
 */
public class ServicesListener {

    private static final String AUTH_SUPPORT_CLASS = "org.apache.sling.auth.core.AuthenticationSupport";
    private static final String AUTHENTICATOR_CLASS = "org.apache.sling.api.auth.Authenticator";
    private static final String REPO_CLASS = "javax.jcr.Repository";
    
    protected static final String WEBCONSOLE_AUTH_TYPE = "sling.webconsole.authType";
    protected static final String JCR_AUTH = "jcrAuth";
    protected static final String SLING_AUTH = "slingAuth";

    /** The bundle context. */
    private final BundleContext bundleContext;

    /** The listener for the repository. */
    private final Listener repositoryListener;

    /** The listener for the authentication support. */
    private final Listener authSupportListener;

    /** The listener for the authenticator. */
    private final Listener authListener;

    enum State {
        NONE,
        PROVIDER_JCR,
        PROVIDER_SLING
    }

    enum AuthType {
        DEFAULT,
        JCR,
        SLING
    }

    /** State */
    private volatile State registrationState = State.NONE;

    /** The registration for the provider */
    private ServiceRegistration<?> providerReg;

    /** The registration for the provider2 */
    private ServiceRegistration<?> provider2Reg;
    
    /** Auth type */
    final AuthType authType;

    /** Logger */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * Start listeners
     */
    public ServicesListener(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        this.authType = getAuthType();
        this.authSupportListener = new Listener(AUTH_SUPPORT_CLASS);
        this.repositoryListener = new Listener(REPO_CLASS);
        this.authListener = new Listener(AUTHENTICATOR_CLASS);
        this.authSupportListener.start();
        this.repositoryListener.start();
        this.authListener.start();
    }

    AuthType getAuthType() {
        final String webConsoleAuthType = bundleContext.getProperty(WEBCONSOLE_AUTH_TYPE);
        if ( webConsoleAuthType != null ) {
            if ( webConsoleAuthType.equals(JCR_AUTH) ) {
                return AuthType.JCR;
            } else if ( webConsoleAuthType.equals(SLING_AUTH) ) {
                return AuthType.SLING;
            }
            logger.error("Ignoring invalid auth type for webconsole security provider {}",  this.authType);
        }
        return AuthType.DEFAULT;
    }

    State getTargetState(final boolean slingAvailable, final boolean jcrAvailable) {
        if ( !slingAvailable && !jcrAvailable ) {
            return State.NONE;
        }
        if ( this.authType == AuthType.JCR && jcrAvailable ) {
            return State.PROVIDER_JCR;
        }
        if ( this.authType == AuthType.SLING && slingAvailable ) {
            return State.PROVIDER_SLING;
        }
        if ( this.authType == AuthType.DEFAULT ) {
            return slingAvailable ? State.PROVIDER_SLING : State.PROVIDER_JCR;
        }
        return State.NONE;
    }

    /**
     * Notify of service changes from the listeners.
     */
    public synchronized void notifyChange() {
        // check if all services are available
        
        final Object authSupport = this.authSupportListener.getService();
        final Object authenticator = this.authListener.getService();
        final Object repository = this.repositoryListener.getService();

        final State targetState = this.getTargetState(authSupport != null && authenticator != null, repository != null);
        if ( this.registrationState != targetState ) {
            if ( targetState != State.PROVIDER_JCR ) {
                this.unregisterProviderJcr();
            } 
            if ( targetState != State.PROVIDER_SLING ) {
                this.unregisterProviderSling();
            }
            if ( targetState == State.PROVIDER_JCR ) {
                this.registerProviderJcr(repository);
            } else if ( targetState == State.PROVIDER_SLING ) {
                this.registerProviderSling(authSupport, authenticator);
            }
            this.registrationState = targetState;
        }
    }

    private void unregisterProviderSling() {
        if ( this.provider2Reg != null ) {
            this.provider2Reg.unregister();
            this.provider2Reg = null;
        }
    }

    private void unregisterProviderJcr() {
        if ( this.providerReg != null ) {
            this.providerReg.unregister();
            this.providerReg = null;
        }
    }

    private void registerProviderSling(final Object authSupport, final Object authenticator) {
        final Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(Constants.SERVICE_PID, SlingWebConsoleSecurityProvider.class.getName());
        props.put(Constants.SERVICE_DESCRIPTION, "Apache Sling Web Console Security Provider 2");
        props.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");
        props.put("webconsole.security.provider.id", "org.apache.sling.extensions.webconsolesecurityprovider2");
        this.provider2Reg = this.bundleContext.registerService(
            new String[] {ManagedService.class.getName(), WebConsoleSecurityProvider.class.getName()},
                          new SlingWebConsoleSecurityProvider2(authSupport, authenticator), props);
    }

    private void registerProviderJcr(final Object repository) {
        final Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(Constants.SERVICE_PID, SlingWebConsoleSecurityProvider.class.getName());
        props.put(Constants.SERVICE_DESCRIPTION, "Apache Sling Web Console Security Provider");
        props.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");
        props.put("webconsole.security.provider.id", "org.apache.sling.extensions.webconsolesecurityprovider");
        this.providerReg = this.bundleContext.registerService(
            new String[] {ManagedService.class.getName(), WebConsoleSecurityProvider.class.getName()}, new SlingWebConsoleSecurityProvider(repository), props);
    }

    /**
     * Deactivate this listener.
     */
    public void deactivate() {
        this.repositoryListener.deactivate();
        this.authSupportListener.deactivate();
        this.authListener.deactivate();
        this.unregisterProviderJcr();
        this.unregisterProviderSling();
    }

    /**
     * Helper class listening for service events for a defined service.
     */
    protected final class Listener implements ServiceListener {

        /** The name of the service. */
        private final String serviceName;

        /** The service reference. */
        private volatile ServiceReference<?> reference;

        /** The service. */
        private volatile Object service;

        /**
         * Constructor
         */
        public Listener(final String serviceName) {
            this.serviceName = serviceName;
        }

        /**
         * Start the listener.
         * First register a service listener and then check for the service.
         */
        public void start() {
            try {
                bundleContext.addServiceListener(this, "("
                        + Constants.OBJECTCLASS + "=" + serviceName + ")");
            } catch (final InvalidSyntaxException ise) {
                // this should really never happen
                throw new RuntimeException("Unexpected exception occured.", ise);
            }
            final ServiceReference<?> ref = bundleContext.getServiceReference(serviceName);
            if ( ref != null ) {
                this.retainService(ref);
            }
        }

        /**
         * Unregister the listener.
         */
        public void deactivate() {
            bundleContext.removeServiceListener(this);
        }

        /**
         * Return the service (if available)
         */
        public synchronized Object getService() {
            return this.service;
        }

        /**
         * Try to get the service and notify the change.
         */
        private synchronized void retainService(final ServiceReference<?> ref) {
            boolean hadService = this.service != null;
            boolean getService = this.reference == null;
            if ( !getService ) {
                final int result = this.reference.compareTo(ref);
                if ( result < 0 ) {
                    bundleContext.ungetService(this.reference);
                    this.service = null;
                    getService = true;
                }
            }
            if ( getService ) {
                this.reference = ref;
                this.service = bundleContext.getService(this.reference);
                if ( this.service == null ) {
                    this.reference = null;
                } else {
                    notifyChange();
                }
            }
            if ( hadService && this.service == null ) {
                notifyChange();
            }
        }

        /**
         * Try to release the service and notify the change.
         */
        private synchronized void releaseService(final ServiceReference<?> ref) {
            if ( this.reference != null && this.reference.compareTo(ref) == 0) {
                this.service = null;
                bundleContext.ungetService(this.reference);
                this.reference = null;
                notifyChange();
            }
        }

        /**
         * @see org.osgi.framework.ServiceListener#serviceChanged(org.osgi.framework.ServiceEvent)
         */
        @Override
        public void serviceChanged(final ServiceEvent event) {
            if (event.getType() == ServiceEvent.REGISTERED) {
                this.retainService(event.getServiceReference());
            } else if ( event.getType() == ServiceEvent.UNREGISTERING ) {
                this.releaseService(event.getServiceReference());
            } else if ( event.getType() == ServiceEvent.MODIFIED ) {
                notifyChange();
            }
        }
    }
}
