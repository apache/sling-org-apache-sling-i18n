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
package org.apache.sling.i18n;

import javax.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.sling.api.SlingHttpServletRequest;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @deprecated use {@link DefaultJakartaLocaleResolverTest} instead
 */
@Deprecated(since = "3.0.0")
public class DefaultLocaleResolverTest {

    /**
     * Test method for {@link org.apache.sling.i18n.DefaultLocaleResolver#resolveLocale(org.apache.sling.api.SlingHttpServletRequest)}.
     */
    @Test
    public void testResolveLocaleSlingHttpServletRequest() {
        SlingHttpServletRequest slingHttpRequest = Mockito.mock(SlingHttpServletRequest.class);
        Mockito.when(slingHttpRequest.getLocales()).thenReturn(Collections.emptyEnumeration());
        DefaultLocaleResolver resolver = new DefaultLocaleResolver();
        List<Locale> locales = resolver.resolveLocale(slingHttpRequest);
        assertNotNull(locales);
        assertTrue(locales.isEmpty());

        Mockito.when(slingHttpRequest.getLocales())
                .thenReturn(Collections.enumeration(List.of(Locale.CANADA, Locale.ENGLISH)));
        locales = resolver.resolveLocale(slingHttpRequest);
        assertNotNull(locales);
        assertEquals(2, locales.size());
        assertTrue(locales.contains(Locale.CANADA));
        assertTrue(locales.contains(Locale.ENGLISH));
    }

    /**
     * Test method for {@link org.apache.sling.i18n.DefaultLocaleResolver#resolveLocale(javax.servlet.http.HttpServletRequest)}.
     */
    @Test
    public void testResolveLocaleHttpServletRequest() {
        HttpServletRequest javaxRequest = Mockito.mock(HttpServletRequest.class);
        Mockito.when(javaxRequest.getLocales()).thenReturn(Collections.emptyEnumeration());
        DefaultLocaleResolver resolver = new DefaultLocaleResolver();
        List<Locale> locales = resolver.resolveLocale(javaxRequest);
        assertNotNull(locales);
        assertTrue(locales.isEmpty());

        Mockito.when(javaxRequest.getLocales())
                .thenReturn(Collections.enumeration(List.of(Locale.CANADA, Locale.ENGLISH)));
        locales = resolver.resolveLocale(javaxRequest);
        assertNotNull(locales);
        assertEquals(2, locales.size());
        assertTrue(locales.contains(Locale.CANADA));
        assertTrue(locales.contains(Locale.ENGLISH));
    }
}
