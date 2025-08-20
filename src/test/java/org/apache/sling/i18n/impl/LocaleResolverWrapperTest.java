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

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.i18n.DefaultLocaleResolver;
import org.apache.sling.i18n.LocaleResolver;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 *
 */
@Deprecated(since = "3.0.0")
public class LocaleResolverWrapperTest {

    /**
     * Test method for {@link org.apache.sling.i18n.impl.LocaleResolverWrapper#getWrapped()}.
     */
    @Test
    public void testGetWrapped() {
        LocaleResolver localeResolver = Mockito.mock(LocaleResolver.class);
        LocaleResolverWrapper wrapper = new LocaleResolverWrapper(localeResolver);
        assertSame(localeResolver, wrapper.getWrapped());
    }

    /**
     * Test method for {@link org.apache.sling.i18n.impl.LocaleResolverWrapper#resolveLocale(jakarta.servlet.http.HttpServletRequest)}.
     */
    @Test
    public void testResolveLocaleWithSlingJakartaRequest() {
        LocaleResolver localeResolver = new DefaultLocaleResolver();
        LocaleResolverWrapper wrapper = new LocaleResolverWrapper(localeResolver);

        SlingJakartaHttpServletRequest slingJakartaRequest = Mockito.mock(SlingJakartaHttpServletRequest.class);
        Mockito.when(slingJakartaRequest.getLocales())
                .thenReturn(Collections.enumeration(List.of(Locale.CANADA, Locale.ENGLISH)));

        List<Locale> locales = wrapper.resolveLocale(slingJakartaRequest);
        assertNotNull(locales);
        assertEquals(2, locales.size());
        assertTrue(locales.contains(Locale.CANADA));
        assertTrue(locales.contains(Locale.ENGLISH));
    }

    @Test
    public void testResolveLocaleWithJakartaRequest() {
        LocaleResolver localeResolver = new DefaultLocaleResolver();
        LocaleResolverWrapper wrapper = new LocaleResolverWrapper(localeResolver);

        HttpServletRequest jakartaRequest = Mockito.mock(HttpServletRequest.class);
        Mockito.when(jakartaRequest.getLocales())
                .thenReturn(Collections.enumeration(List.of(Locale.CANADA, Locale.ENGLISH)));

        List<Locale> locales = wrapper.resolveLocale(jakartaRequest);
        assertNotNull(locales);
        assertTrue(locales.isEmpty());
    }
}
