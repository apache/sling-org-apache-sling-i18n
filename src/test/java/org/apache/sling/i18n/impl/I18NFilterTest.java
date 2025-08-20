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
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.wrappers.SlingJakartaHttpServletRequestWrapper;
import org.apache.sling.i18n.DefaultJakartaLocaleResolver;
import org.apache.sling.i18n.JakartaRequestLocaleResolver;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 *
 */
public class I18NFilterTest {

    private I18NFilter filter = new I18NFilter();

    /**
     * Test method for {@link org.apache.sling.i18n.impl.I18NFilter#doFilter(jakarta.servlet.ServletRequest, jakarta.servlet.ServletResponse, jakarta.servlet.FilterChain)}.
     */
    @Test
    public void testDoFilterWithSlingJakartaHttpServletRequest() throws IOException, ServletException {
        SlingJakartaHttpServletRequest request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        SlingJakartaHttpServletResponse response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        FilterChain chain = Mockito.mock(FilterChain.class);
        AtomicReference<ServletRequest> reqHolder = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
                    ServletRequest req = invocation.getArgument(0, ServletRequest.class);
                    reqHolder.set(req);
                    return null;
                })
                .when(chain)
                .doFilter(any(ServletRequest.class), any(ServletResponse.class));
        filter.doFilter(request, response, chain);
        ServletRequest invokedRequest = reqHolder.get();
        assertTrue(invokedRequest instanceof SlingJakartaHttpServletRequestWrapper);
        assertSame(request, ((SlingJakartaHttpServletRequestWrapper) invokedRequest).getRequest());
    }

    @Test
    public void testDoFilterWithJakartaHttpServletRequest() throws IOException, ServletException {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        FilterChain chain = Mockito.mock(FilterChain.class);
        AtomicReference<ServletRequest> reqHolder = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
                    ServletRequest req = invocation.getArgument(0, ServletRequest.class);
                    reqHolder.set(req);
                    return null;
                })
                .when(chain)
                .doFilter(any(ServletRequest.class), any(ServletResponse.class));
        filter.doFilter(request, response, chain);
        ServletRequest invokedRequest = reqHolder.get();
        assertTrue(invokedRequest instanceof HttpServletRequestWrapper);
        assertSame(request, ((HttpServletRequestWrapper) invokedRequest).getRequest());
    }

    /**
     * Test method for {@link org.apache.sling.i18n.impl.I18NFilter#bindLocaleResolver(org.apache.sling.i18n.LocaleResolver)}.
     * @deprecated use {@link #testBindJakartaRequestLocaleResolver()} instead
     */
    @Deprecated(since = "3.0.0")
    @Test
    public void testBindLocaleResolver() {
        org.apache.sling.i18n.LocaleResolver localeResolver = new org.apache.sling.i18n.DefaultLocaleResolver();
        filter.bindLocaleResolver(localeResolver);

        // the bound object should be now be the current resolver
        JakartaRequestLocaleResolver bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof LocaleResolverWrapper);
        assertSame(localeResolver, ((LocaleResolverWrapper) bestLocaleResolver).getWrapped());
    }

    /**
     * Test method for {@link org.apache.sling.i18n.impl.I18NFilter#unbindLocaleResolver(org.apache.sling.i18n.LocaleResolver)}.
     * @deprecated use {@link #testUnbindJakartaRequestLocaleResolver()} instead
     */
    @Deprecated(since = "3.0.0")
    @Test
    public void testUnbindLocaleResolver() {
        org.apache.sling.i18n.LocaleResolver localeResolver = new org.apache.sling.i18n.DefaultLocaleResolver();
        filter.bindLocaleResolver(localeResolver);

        // the bound object should be now be the current resolver
        JakartaRequestLocaleResolver bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof LocaleResolverWrapper);
        assertSame(localeResolver, ((LocaleResolverWrapper) bestLocaleResolver).getWrapped());

        filter.unbindLocaleResolver(localeResolver);
        // should be back to the default locale resolver
        bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof DefaultJakartaLocaleResolver);
    }

    /**
     * Test method for {@link org.apache.sling.i18n.impl.I18NFilter#bindRequestLocaleResolver(org.apache.sling.i18n.RequestLocaleResolver)}.
     * @deprecated use {@link #testBindJakartaRequestLocaleResolver()} instead
     */
    @Deprecated(since = "3.0.0")
    @Test
    public void testBindRequestLocaleResolver() {
        org.apache.sling.i18n.RequestLocaleResolver requestLocaleResolver =
                new org.apache.sling.i18n.DefaultLocaleResolver();
        filter.bindRequestLocaleResolver(requestLocaleResolver);

        // the bound object should be now be the current resolver
        JakartaRequestLocaleResolver bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof RequestLocaleResolverWrapper);
        assertSame(requestLocaleResolver, ((RequestLocaleResolverWrapper) bestLocaleResolver).getWrapped());
    }

    /**
     * Test method for {@link org.apache.sling.i18n.impl.I18NFilter#unbindRequestLocaleResolver(org.apache.sling.i18n.RequestLocaleResolver)}.
     * @deprecated use {@link #testUnbindJakartaRequestLocaleResolver()} instead
     */
    @Deprecated(since = "3.0.0")
    @Test
    public void testUnbindRequestLocaleResolver() {
        org.apache.sling.i18n.RequestLocaleResolver requestLocaleResolver =
                new org.apache.sling.i18n.DefaultLocaleResolver();
        filter.bindRequestLocaleResolver(requestLocaleResolver);

        // the bound object should be now be the current resolver
        JakartaRequestLocaleResolver bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof RequestLocaleResolverWrapper);
        assertSame(requestLocaleResolver, ((RequestLocaleResolverWrapper) bestLocaleResolver).getWrapped());

        filter.unbindRequestLocaleResolver(requestLocaleResolver);
        // should be back to the default locale resolver
        bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof DefaultJakartaLocaleResolver);
    }

    /**
     * Test method for {@link org.apache.sling.i18n.impl.I18NFilter#bindJakartaRequestLocaleResolver(org.apache.sling.i18n.JakartaRequestLocaleResolver)}.
     */
    @Test
    public void testBindJakartaRequestLocaleResolver() {
        JakartaRequestLocaleResolver jakartaRequestLocaleResolver = new DefaultJakartaLocaleResolver();
        filter.bindJakartaRequestLocaleResolver(jakartaRequestLocaleResolver);

        // the bound object should be now be the current resolver
        JakartaRequestLocaleResolver bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertSame(jakartaRequestLocaleResolver, bestLocaleResolver);
    }

    /**
     * Test method for {@link org.apache.sling.i18n.impl.I18NFilter#unbindJakartaRequestLocaleResolver(org.apache.sling.i18n.JakartaRequestLocaleResolver)}.
     */
    @Test
    public void testUnbindJakartaRequestLocaleResolver() {
        JakartaRequestLocaleResolver jakartaRequestLocaleResolver = new DefaultJakartaLocaleResolver();
        filter.bindJakartaRequestLocaleResolver(jakartaRequestLocaleResolver);

        // the bound object should be now be the current resolver
        JakartaRequestLocaleResolver bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertSame(jakartaRequestLocaleResolver, bestLocaleResolver);

        filter.unbindJakartaRequestLocaleResolver(jakartaRequestLocaleResolver);
        // should be back to the default locale resolver
        bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertNotSame(jakartaRequestLocaleResolver, bestLocaleResolver);
        assertTrue(bestLocaleResolver instanceof DefaultJakartaLocaleResolver);
    }

    /**
     * Test method for {@link org.apache.sling.i18n.impl.I18NFilter#calculateBestLocaleResolver()}.
     */
    @SuppressWarnings("deprecation")
    @Test
    public void testCalculateBestLocaleResolver() {
        JakartaRequestLocaleResolver defaultLocaleResolver = filter.calculateBestLocaleResolver();
        assertNotNull(defaultLocaleResolver);

        org.apache.sling.i18n.LocaleResolver localeResolver = new org.apache.sling.i18n.DefaultLocaleResolver();
        filter.bindLocaleResolver(localeResolver);
        JakartaRequestLocaleResolver bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof LocaleResolverWrapper);
        assertSame(localeResolver, ((LocaleResolverWrapper) bestLocaleResolver).getWrapped());

        org.apache.sling.i18n.RequestLocaleResolver requestLocaleResolver =
                new org.apache.sling.i18n.DefaultLocaleResolver();
        filter.bindRequestLocaleResolver(requestLocaleResolver);
        bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof RequestLocaleResolverWrapper);
        assertSame(requestLocaleResolver, ((RequestLocaleResolverWrapper) bestLocaleResolver).getWrapped());

        JakartaRequestLocaleResolver jakartaRequestLocaleResolver = new DefaultJakartaLocaleResolver();
        filter.bindJakartaRequestLocaleResolver(jakartaRequestLocaleResolver);
        bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertSame(jakartaRequestLocaleResolver, bestLocaleResolver);

        filter.unbindJakartaRequestLocaleResolver(jakartaRequestLocaleResolver);
        bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof RequestLocaleResolverWrapper);
        assertSame(requestLocaleResolver, ((RequestLocaleResolverWrapper) bestLocaleResolver).getWrapped());

        filter.unbindRequestLocaleResolver(requestLocaleResolver);
        bestLocaleResolver = filter.calculateBestLocaleResolver();
        assertTrue(bestLocaleResolver instanceof LocaleResolverWrapper);
        assertSame(localeResolver, ((LocaleResolverWrapper) bestLocaleResolver).getWrapped());

        filter.unbindLocaleResolver(localeResolver);
        assertSame(defaultLocaleResolver, filter.calculateBestLocaleResolver());
    }
}
