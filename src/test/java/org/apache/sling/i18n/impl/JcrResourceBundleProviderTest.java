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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;


public class JcrResourceBundleProviderTest {
    
    @Rule
    public final SlingContext context = new SlingContext();

    @Test
    public void testToLocale() {
        // empty string must return default locale
        Assert.assertEquals(Locale.getDefault(), JcrResourceBundleProvider.toLocale(""));

        // only language part being set
        Assert.assertEquals(Locale.ENGLISH, JcrResourceBundleProvider.toLocale("en"));
        Assert.assertEquals(Locale.GERMAN, JcrResourceBundleProvider.toLocale("de"));
        // for invalid languages assume default language
        Assert.assertEquals(new Locale(Locale.getDefault().getLanguage()), JcrResourceBundleProvider.toLocale("invalid"));

        // both language and country being set (no matter whether lower or upper case)
        Assert.assertEquals(Locale.GERMANY, JcrResourceBundleProvider.toLocale("de_DE"));
        Assert.assertEquals(Locale.GERMANY, JcrResourceBundleProvider.toLocale("de_de"));
        Assert.assertEquals(Locale.GERMANY, JcrResourceBundleProvider.toLocale("DE_de"));

        Assert.assertEquals(Locale.UK, JcrResourceBundleProvider.toLocale("en_GB"));
        Assert.assertEquals(Locale.UK, JcrResourceBundleProvider.toLocale("en_gb"));
        Assert.assertEquals(Locale.UK, JcrResourceBundleProvider.toLocale("EN_gb"));
        // for invalid languages assume default language
        Assert.assertEquals(new Locale(Locale.getDefault().getLanguage(), "GB"), JcrResourceBundleProvider.toLocale("invalid_GB"));
        // for invalid countries assume default country
        Assert.assertEquals(new Locale("en", Locale.getDefault().getCountry()), JcrResourceBundleProvider.toLocale("en_invalid"));

        // language, country and variant being set
        Assert.assertEquals(new Locale(Locale.UK.getLanguage(), Locale.UK.getCountry(), "variant1"), JcrResourceBundleProvider.toLocale("en_GB_variant1"));

        // parts after the variant are just ignored
        Assert.assertEquals(new Locale(Locale.UK.getLanguage(), Locale.UK.getCountry(), "variant1"), JcrResourceBundleProvider.toLocale("en_GB_variant1_something"));
    }

    @Test
    public void testToLocaleWithBcp47CompliantStrings() {
        // both language and country being set
        Assert.assertEquals(Locale.GERMANY, JcrResourceBundleProvider.toLocale("de-DE"));

        Assert.assertEquals(Locale.UK, JcrResourceBundleProvider.toLocale("en-GB"));
        // for invalid languages assume default language
        Assert.assertEquals(new Locale(Locale.getDefault().getLanguage(), "GB"), JcrResourceBundleProvider.toLocale("invalid-GB"));
        // for invalid countries assume default country
        Assert.assertEquals(new Locale("en", Locale.getDefault().getCountry()), JcrResourceBundleProvider.toLocale("en-invalid"));
        // language, country and variant being set
        Assert.assertEquals(new Locale(Locale.UK.getLanguage(), Locale.UK.getCountry(), "variant1"), JcrResourceBundleProvider.toLocale("en-GB-variant1"));

        // parts after the variant are just ignored
        Assert.assertEquals(new Locale(Locale.UK.getLanguage(), Locale.UK.getCountry(), "variant1"), JcrResourceBundleProvider.toLocale("en-GB-variant1-something-else"));
    }

    @Test
    public void testToLocaleWithPrivateUseCountryCode() {
        // Private use Country 'XZ'
        Assert.assertEquals(new Locale(Locale.GERMAN.getLanguage(), "XZ"), JcrResourceBundleProvider.toLocale("de_XZ"));

        // Private use Country 'AA'
        Assert.assertEquals(new Locale(Locale.GERMAN.getLanguage(), "AA"), JcrResourceBundleProvider.toLocale("de_AA"));

        // Private use Country 'QX'
        Assert.assertEquals(new Locale(Locale.GERMAN.getLanguage(), "QX"), JcrResourceBundleProvider.toLocale("de_QX"));

        // Private use Country 'ZZ'
        Assert.assertEquals(new Locale(Locale.GERMAN.getLanguage(), "ZZ"), JcrResourceBundleProvider.toLocale("de_ZZ"));

        // for invalid countries assume default country
        Assert.assertEquals(new Locale("en", Locale.getDefault().getCountry()), JcrResourceBundleProvider.toLocale("en-QB"));

        // Lowercase Private use Country 'xa'
        Assert.assertEquals(new Locale(Locale.GERMAN.getLanguage(), "XA"), JcrResourceBundleProvider.toLocale("de_xa"));
    }
    
    @Test
    public void testPathExclusions() {
        JcrResourceBundleProvider sut = new JcrResourceBundleProvider();
        Map<String,Object> props = new HashMap<>();
        props.put("excluded.paths", new String[] {"/excluded/path"});
        
        Scheduler scheduler = Mockito.mock(Scheduler.class);
        Mockito.when(scheduler.schedule(Mockito.any(), Mockito.any())).thenReturn(false); // is ignored
        Mockito.when(scheduler.AT(Mockito.any())).thenReturn(Mockito.mock(ScheduleOptions.class));
        Mockito.when(scheduler.NOW()).thenReturn(Mockito.mock(ScheduleOptions.class));
        context.registerService(Scheduler.class,scheduler);
        
        ServiceUserMapped serviceUserMapped = Mockito.mock(ServiceUserMapped.class);
        context.registerService(ServiceUserMapped.class,serviceUserMapped);
        
        context.registerInjectActivateService(sut, props);
        ResourceChange c1 = Mockito.mock(ResourceChange.class);
        Mockito.when(c1.getPath()).thenReturn("/excluded/path");
        ResourceChange c2 = Mockito.mock(ResourceChange.class);
        Mockito.when(c2.getPath()).thenReturn("/another/path");
        ResourceChange c3 = Mockito.mock(ResourceChange.class);
        Mockito.when(c3.getPath()).thenReturn("/excluded/path/node");
        
        assertTrue(sut.canIgnoreChange(c1));
        assertFalse(sut.canIgnoreChange(c2));
        assertTrue(sut.canIgnoreChange(c3));
    }

}
