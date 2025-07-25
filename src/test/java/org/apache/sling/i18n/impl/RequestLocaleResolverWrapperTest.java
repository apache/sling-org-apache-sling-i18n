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
import org.apache.sling.i18n.DefaultLocaleResolver;
import org.apache.sling.i18n.RequestLocaleResolver;
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
public class RequestLocaleResolverWrapperTest {

    /**
     * Test method for {@link org.apache.sling.i18n.impl.RequestLocaleResolverWrapper#getWrapped()}.
     */
    @Test
    public void testGetWrapped() {
        RequestLocaleResolver localeResolver = Mockito.mock(RequestLocaleResolver.class);
        RequestLocaleResolverWrapper wrapper = new RequestLocaleResolverWrapper(localeResolver);
        assertSame(localeResolver, wrapper.getWrapped());
    }

    /**
     * Test method for {@link org.apache.sling.i18n.impl.RequestLocaleResolverWrapper#resolveLocale(jakarta.servlet.http.HttpServletRequest)}.
     */
    @Test
    public void testResolveLocale() {
        RequestLocaleResolver localeResolver = new DefaultLocaleResolver();
        RequestLocaleResolverWrapper wrapper = new RequestLocaleResolverWrapper(localeResolver);

        HttpServletRequest javaxRequest = Mockito.mock(HttpServletRequest.class);
        Mockito.when(javaxRequest.getLocales())
                .thenReturn(Collections.enumeration(List.of(Locale.CANADA, Locale.ENGLISH)));

        List<Locale> locales = wrapper.resolveLocale(javaxRequest);
        assertNotNull(locales);
        assertEquals(2, locales.size());
        assertTrue(locales.contains(Locale.CANADA));
        assertTrue(locales.contains(Locale.ENGLISH));
    }
}
