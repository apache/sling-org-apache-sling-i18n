/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.i18n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.sling.api.SlingHttpServletRequest;
import org.junit.Test;
import org.mockito.Mockito;

/**
 *
 */
public class DefaultLocaleResolverTest {

    private DefaultLocaleResolver localeResolver = new DefaultLocaleResolver();

    /**
     * Test method for {@link org.apache.sling.i18n.DefaultLocaleResolver#resolveLocale(org.apache.sling.api.SlingHttpServletRequest)}.
     */
    @Test
    public void testResolveLocaleSlingHttpServletRequest() {
        SlingHttpServletRequest req = Mockito.mock(SlingHttpServletRequest.class);
        Mockito.doReturn(Collections.enumeration(Arrays.asList(Locale.ENGLISH, Locale.GERMAN)))
            .when(req).getLocales();
        List<Locale> locales = localeResolver.resolveLocale(req);
        assertNotNull(locales);
        assertEquals(2, locales.size());
    }

    /**
     * Test method for {@link org.apache.sling.i18n.DefaultLocaleResolver#resolveLocale(javax.servlet.http.HttpServletRequest)}.
     */
    @Test
    public void testResolveLocaleHttpServletRequest() {
        javax.servlet.http.HttpServletRequest req = Mockito.mock(javax.servlet.http.HttpServletRequest.class);
        Mockito.doReturn(Collections.enumeration(Arrays.asList(Locale.ENGLISH, Locale.GERMAN)))
            .when(req).getLocales();
        List<Locale> locales = localeResolver.resolveLocale(req);
        assertNotNull(locales);
        assertEquals(2, locales.size());
    }

    /**
     * Test method for {@link org.apache.sling.i18n.DefaultLocaleResolver#resolveLocale(jakarta.servlet.http.HttpServletRequest)}.
     */
    @Test
    public void testResolveLocaleHttpServletRequest1() {
        jakarta.servlet.http.HttpServletRequest req = Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        Mockito.doReturn(Collections.enumeration(Arrays.asList(Locale.ENGLISH, Locale.GERMAN)))
            .when(req).getLocales();
        List<Locale> locales = localeResolver.resolveLocale(req);
        assertNotNull(locales);
        assertEquals(2, locales.size());
    }

}
