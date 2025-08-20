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
package org.apache.sling.i18n.impl;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.TreeMap;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.wrappers.SlingJakartaHttpServletRequestWrapper;
import org.apache.sling.commons.osgi.Order;
import org.apache.sling.commons.osgi.ServiceUtil;
import org.apache.sling.i18n.DefaultJakartaLocaleResolver;
import org.apache.sling.i18n.JakartaRequestLocaleResolver;
import org.apache.sling.i18n.ResourceBundleProvider;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>I18NFilter</code> class is a request level filter, which provides
 * the resource bundle for the current request.
 */
@Component(
        service = Filter.class,
        property = {
            Constants.SERVICE_DESCRIPTION + "=Internationalization Support Filter",
            Constants.SERVICE_VENDOR + "=The Apache Software Foundation",
            Constants.SERVICE_RANKING + ":Integer=700",
            "sling.filter.scope=REQUEST",
            "sling.filter.scope=ERROR",
            HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN + "=/",
            HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
                    + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=*)"
        })
public class I18NFilter implements Filter {

    /**
     * The default server default locale if not configured <code>Locale.ENGLISH</code>.
     */
    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(I18NFilter.class.getName());

    private final DefaultJakartaLocaleResolver defaultLocaleResolver = new DefaultJakartaLocaleResolver();

    /**
     * We can have potentially 3 different kinds of bound LocaleResolvers, so store
     * the candidates here (ordered by preference) and then resolve which to use via
     * the {@link #calculateBestLocaleResolver()} method
     */
    private final JakartaRequestLocaleResolver[] localeResolvers = new JakartaRequestLocaleResolver[] {
        null, // for bound JakartaRequestLocaleResolver
        null, // for deprecated bound RequestLocaleResolver
        null // for deprecated bound LocaleResolver
    };

    /**
     * The current best locale resolver
     */
    private JakartaRequestLocaleResolver requestLocaleResolver = defaultLocaleResolver;

    private final Map<Object, ResourceBundleProvider> providers = new TreeMap<>();

    private volatile ResourceBundleProvider[] sortedProviders = new ResourceBundleProvider[0];

    private final ResourceBundleProvider combinedProvider = new CombinedBundleProvider();

    /**
     * @see jakarta.servlet.Filter#doFilter(jakarta.servlet.ServletRequest, jakarta.servlet.ServletResponse, jakarta.servlet.FilterChain)
     */
    @Override
    public void doFilter(ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof SlingJakartaHttpServletRequest) {
            request = new I18NSlingJakartaHttpServletRequest(request, combinedProvider, requestLocaleResolver);
        } else {
            request = new I18NHttpServletRequest(request, combinedProvider, requestLocaleResolver);
        }

        // and forward the request
        chain.doFilter(request, response);
    }

    // ---------- SCR Integration ----------------------------------------------

    /**
     * Given all the bound locale resolver candidates, resolve the preferred one.
     * Use the non-deprecated candidate if we have one, or fallback to the least
     * deprecated variant otherwise.
     */
    protected JakartaRequestLocaleResolver calculateBestLocaleResolver() {
        return Arrays.stream(localeResolvers)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(defaultLocaleResolver);
    }

    /**
     * @deprecated use {@link #bindJakartaRequestLocaleResolver(JakartaRequestLocaleResolver)} instead
     */
    @Deprecated(since = "3.0.0")
    @Reference(
            cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC,
            policyOption = ReferencePolicyOption.GREEDY)
    protected void bindLocaleResolver(final org.apache.sling.i18n.LocaleResolver resolver) {
        synchronized (localeResolvers) {
            // wrap it with a JakartaRequestLocaleResolver impl
            this.localeResolvers[2] = new LocaleResolverWrapper(resolver);
            this.requestLocaleResolver = calculateBestLocaleResolver();
        }
    }

    /**
     * @deprecated use {@link #unbindJakartaRequestLocaleResolver(JakartaRequestLocaleResolver)} instead
     */
    @Deprecated(since = "3.0.0")
    protected void unbindLocaleResolver(final org.apache.sling.i18n.LocaleResolver resolver) {
        synchronized (localeResolvers) {
            this.localeResolvers[2] = null;
            this.requestLocaleResolver = calculateBestLocaleResolver();
        }
    }

    /**
     * @deprecated use {@link #bindJakartaRequestLocaleResolver(JakartaRequestLocaleResolver)} instead
     */
    @Deprecated(since = "3.0.0")
    @Reference(
            cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC,
            policyOption = ReferencePolicyOption.GREEDY)
    protected void bindRequestLocaleResolver(final org.apache.sling.i18n.RequestLocaleResolver resolver) {
        synchronized (localeResolvers) {
            // wrap it with a JakartaRequestLocaleResolver impl
            this.localeResolvers[1] = new RequestLocaleResolverWrapper(resolver);
            this.requestLocaleResolver = calculateBestLocaleResolver();
        }
    }

    /**
     * @deprecated use {@link #bindJakartaRequestLocaleResolver(JakartaRequestLocaleResolver)} instead
     */
    @Deprecated(since = "3.0.0")
    protected void unbindRequestLocaleResolver(final org.apache.sling.i18n.RequestLocaleResolver resolver) {
        synchronized (localeResolvers) {
            this.localeResolvers[1] = null;
            this.requestLocaleResolver = calculateBestLocaleResolver();
        }
    }

    @Reference(
            cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC,
            policyOption = ReferencePolicyOption.GREEDY)
    protected void bindJakartaRequestLocaleResolver(final JakartaRequestLocaleResolver resolver) {
        synchronized (localeResolvers) {
            this.localeResolvers[0] = resolver;
            this.requestLocaleResolver = calculateBestLocaleResolver();
        }
    }

    protected void unbindJakartaRequestLocaleResolver(final JakartaRequestLocaleResolver resolver) { // NOSONAR
        synchronized (localeResolvers) {
            this.localeResolvers[0] = null;
            this.requestLocaleResolver = calculateBestLocaleResolver();
        }
    }

    @Reference(
            service = ResourceBundleProvider.class,
            cardinality = ReferenceCardinality.MULTIPLE,
            policy = ReferencePolicy.DYNAMIC)
    protected void bindResourceBundleProvider(final ResourceBundleProvider provider, final Map<String, Object> props) {
        synchronized (this.providers) {
            this.providers.put(ServiceUtil.getComparableForServiceRanking(props, Order.ASCENDING), provider);
            this.sortedProviders = this.providers.values().toArray(new ResourceBundleProvider[this.providers.size()]);
        }
    }

    protected void unbindResourceBundleProvider(
            final ResourceBundleProvider provider, final Map<String, Object> props) {
        synchronized (this.providers) {
            this.providers.remove(ServiceUtil.getComparableForServiceRanking(props, Order.ASCENDING), provider);
            this.sortedProviders = this.providers.values().toArray(new ResourceBundleProvider[this.providers.size()]);
        }
    }

    // ---------- internal -----------------------------------------------------

    /** Provider that goes through a list of registered providers and takes the first non-null responses */
    private class CombinedBundleProvider implements ResourceBundleProvider {

        @Override
        public Locale getDefaultLocale() {
            // ask all registered providers, use the first one that returns
            final ResourceBundleProvider[] providers = sortedProviders;
            for (int i = providers.length - 1; i >= 0; i--) {
                final ResourceBundleProvider provider = providers[i];
                final Locale locale = provider.getDefaultLocale();
                if (locale != null) {
                    return locale;
                }
            }
            return null;
        }

        @Override
        public ResourceBundle getResourceBundle(final Locale locale) {
            // ask all registered providers, use the first one that returns
            final ResourceBundleProvider[] providers = sortedProviders;
            for (int i = providers.length - 1; i >= 0; i--) {
                final ResourceBundleProvider provider = providers[i];
                final ResourceBundle bundle = provider.getResourceBundle(locale);
                if (bundle != null) {
                    return bundle;
                }
            }
            return null;
        }

        @Override
        public ResourceBundle getResourceBundle(final String baseName, final Locale locale) {
            // ask all registered providers, use the first one that returns
            final ResourceBundleProvider[] providers = sortedProviders;
            for (int i = providers.length - 1; i >= 0; i--) {
                final ResourceBundleProvider provider = providers[i];
                final ResourceBundle bundle = provider.getResourceBundle(baseName, locale);
                if (bundle != null) {
                    return bundle;
                }
            }
            return null;
        }
    }

    private static Locale defaultLocale(ResourceBundleProvider bundleProvider) {
        Locale defaultLocale = bundleProvider.getDefaultLocale();
        return (defaultLocale != null) ? defaultLocale : DEFAULT_LOCALE;
    }

    // ---------- internal class -----------------------------------------------

    private static class I18NHttpServletRequest extends HttpServletRequestWrapper {

        private final ResourceBundleProvider bundleProvider;

        private final JakartaRequestLocaleResolver localeResolver;

        private Locale locale;

        private List<Locale> localeList;

        private ResourceBundle resourceBundle;

        I18NHttpServletRequest(
                final ServletRequest delegatee,
                final ResourceBundleProvider bundleProvider,
                final JakartaRequestLocaleResolver localeResolver) {
            super((HttpServletRequest) delegatee);
            this.bundleProvider = bundleProvider;
            this.localeResolver = localeResolver;
        }

        @Override
        public Locale getLocale() {
            if (locale == null) {
                locale = this.getLocaleList().get(0);
            }

            return locale;
        }

        @Override
        public Enumeration<Locale> getLocales() {
            return Collections.enumeration(getLocaleList());
        }

        @Override
        public Object getAttribute(final String name) {
            if (ResourceBundleProvider.BUNDLE_REQ_ATTR.equals(name)) {
                if (this.resourceBundle == null && this.bundleProvider != null) {
                    this.resourceBundle = this.bundleProvider.getResourceBundle(this.getLocale());
                }
                return this.resourceBundle;
            }
            return super.getAttribute(name);
        }

        private List<Locale> getLocaleList() {
            if (localeList == null) {
                List<Locale> resolved = localeResolver.resolveLocale((HttpServletRequest) this.getRequest());
                this.localeList = (resolved != null && !resolved.isEmpty())
                        ? resolved
                        : Collections.singletonList(defaultLocale(this.bundleProvider));
            }

            return localeList;
        }
    }

    private static class I18NSlingJakartaHttpServletRequest extends SlingJakartaHttpServletRequestWrapper {

        private final ResourceBundleProvider bundleProvider;
        private final JakartaRequestLocaleResolver localeResolver;

        private Locale locale;

        private List<Locale> localeList;

        I18NSlingJakartaHttpServletRequest(
                final ServletRequest delegatee,
                final ResourceBundleProvider bundleProvider,
                final JakartaRequestLocaleResolver localeResolver) {
            super((SlingJakartaHttpServletRequest) delegatee);
            this.bundleProvider = bundleProvider;
            this.localeResolver = localeResolver;
        }

        @Override
        public ResourceBundle getResourceBundle(Locale locale) {
            return getResourceBundle(null, locale);
        }

        @Override
        public ResourceBundle getResourceBundle(String baseName, Locale locale) {
            if (bundleProvider != null) {
                if (locale == null) {
                    locale = getLocale();
                }

                try {
                    return bundleProvider.getResourceBundle(baseName, locale);
                } catch (MissingResourceException mre) {
                    LOG.warn("getResourceBundle: Cannot get ResourceBundle from provider", mre);
                }
            } else {
                LOG.info("getResourceBundle: ResourceBundleProvider not available, calling default implementation");
            }

            return super.getResourceBundle(baseName, locale);
        }

        @Override
        public Object getAttribute(final String name) {
            if (ResourceBundleProvider.BUNDLE_REQ_ATTR.equals(name)) {
                final Object superValue = super.getAttribute(name);
                return (superValue != null ? superValue : this.getResourceBundle(null));
            }
            return super.getAttribute(name);
        }

        @Override
        public Locale getLocale() {
            if (locale == null) {
                locale = this.getLocaleList().get(0);
            }

            return locale;
        }

        @Override
        public Enumeration<Locale> getLocales() {
            return Collections.enumeration(getLocaleList());
        }

        private List<Locale> getLocaleList() {
            if (localeList == null) {
                List<Locale> resolved = localeResolver.resolveLocale(this.getSlingRequest());
                this.localeList = (resolved != null && !resolved.isEmpty())
                        ? resolved
                        : Collections.singletonList(defaultLocale(this.bundleProvider));
            }

            return localeList;
        }
    }
}
