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

import java.util.Locale;

import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

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
        Assert.assertEquals(
                new Locale(Locale.getDefault().getLanguage()), JcrResourceBundleProvider.toLocale("invalid"));

        // both language and country being set (no matter whether lower or upper case)
        Assert.assertEquals(Locale.GERMANY, JcrResourceBundleProvider.toLocale("de_DE"));
        Assert.assertEquals(Locale.GERMANY, JcrResourceBundleProvider.toLocale("de_de"));
        Assert.assertEquals(Locale.GERMANY, JcrResourceBundleProvider.toLocale("DE_de"));

        Assert.assertEquals(Locale.UK, JcrResourceBundleProvider.toLocale("en_GB"));
        Assert.assertEquals(Locale.UK, JcrResourceBundleProvider.toLocale("en_gb"));
        Assert.assertEquals(Locale.UK, JcrResourceBundleProvider.toLocale("EN_gb"));
        // for invalid languages assume default language
        Assert.assertEquals(
                new Locale(Locale.getDefault().getLanguage(), "GB"), JcrResourceBundleProvider.toLocale("invalid_GB"));
        // for invalid countries assume default country
        Assert.assertEquals(
                new Locale("en", Locale.getDefault().getCountry()), JcrResourceBundleProvider.toLocale("en_invalid"));

        // language, country and variant being set
        Assert.assertEquals(
                new Locale(Locale.UK.getLanguage(), Locale.UK.getCountry(), "variant1"),
                JcrResourceBundleProvider.toLocale("en_GB_variant1"));

        // parts after the variant are just ignored
        Assert.assertEquals(
                new Locale(Locale.UK.getLanguage(), Locale.UK.getCountry(), "variant1"),
                JcrResourceBundleProvider.toLocale("en_GB_variant1_something"));

        // language and script being set
        Assert.assertEquals(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setScript("hans")
                        .build(),
                JcrResourceBundleProvider.toLocale("zh_hans"));

        // language, script, country and variant being set
        Assert.assertEquals(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setRegion(Locale.CHINA.getCountry())
                        .setScript("hans")
                        .setVariant("variant1")
                        .build(),
                JcrResourceBundleProvider.toLocale("zh_hans_cn_variant1"));

        // parts after the variant are just ignored
        Assert.assertEquals(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setRegion(Locale.CHINA.getCountry())
                        .setScript("hans")
                        .setVariant("variant1")
                        .build(),
                JcrResourceBundleProvider.toLocale("zh_hans_cn_variant1_variant2"));

        // language, script and country being set
        Assert.assertEquals(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setRegion(Locale.CHINA.getCountry())
                        .setScript("hans")
                        .build(),
                JcrResourceBundleProvider.toLocale("zh_hans_cn"));

        // for invalid country and invalid script, default country is assumed
        Assert.assertEquals(
                new Locale("en", Locale.getDefault().getCountry()), JcrResourceBundleProvider.toLocale("en_Han1"));
    }

    @Test
    public void testToLocaleWithBcp47CompliantStrings() {
        // both language and country being set
        Assert.assertEquals(Locale.GERMANY, JcrResourceBundleProvider.toLocale("de-DE"));

        Assert.assertEquals(Locale.UK, JcrResourceBundleProvider.toLocale("en-GB"));
        // for invalid languages assume default language
        Assert.assertEquals(
                new Locale(Locale.getDefault().getLanguage(), "GB"), JcrResourceBundleProvider.toLocale("invalid-GB"));
        // for invalid countries assume default country
        Assert.assertEquals(
                new Locale("en", Locale.getDefault().getCountry()), JcrResourceBundleProvider.toLocale("en-invalid"));
        // language, country and variant being set
        Assert.assertEquals(
                new Locale(Locale.UK.getLanguage(), Locale.UK.getCountry(), "variant1"),
                JcrResourceBundleProvider.toLocale("en-GB-variant1"));

        // parts after the variant are just ignored
        Assert.assertEquals(
                new Locale(Locale.UK.getLanguage(), Locale.UK.getCountry(), "variant1"),
                JcrResourceBundleProvider.toLocale("en-GB-variant1-something-else"));

        // language, script, country and variant being set
        Assert.assertEquals(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setRegion(Locale.CHINA.getCountry())
                        .setScript("hans")
                        .setVariant("variant1")
                        .build(),
                JcrResourceBundleProvider.toLocale("zh-hans-cn-variant1"));

        // parts after the variant are just ignored
        Assert.assertEquals(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setRegion(Locale.CHINA.getCountry())
                        .setScript("hans")
                        .setVariant("variant1")
                        .build(),
                JcrResourceBundleProvider.toLocale("zh-hans-cn-variant1-variant2"));

        // language, script and country being set
        Assert.assertEquals(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setRegion(Locale.CHINA.getCountry())
                        .setScript("hans")
                        .build(),
                JcrResourceBundleProvider.toLocale("zh-hans-cn"));
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
        Assert.assertEquals(
                new Locale("en", Locale.getDefault().getCountry()), JcrResourceBundleProvider.toLocale("en-QB"));

        // Lowercase Private use Country 'xa'
        Assert.assertEquals(new Locale(Locale.GERMAN.getLanguage(), "XA"), JcrResourceBundleProvider.toLocale("de_xa"));
    }

    @Test
    public void testGetParentLocale() {
        JcrResourceBundleProvider provider = new JcrResourceBundleProvider();
        String variant = "variant1";
        String script = "Hans";
        // Locale with script and variant
        Locale locale = new Locale.Builder()
                .setLanguage(Locale.CHINA.getLanguage())
                .setScript(script)
                .setRegion(Locale.CHINA.getCountry())
                .setVariant(variant)
                .build();
        Assert.assertEquals(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setScript(script)
                        .setRegion(Locale.CHINA.getCountry())
                        .build(),
                provider.getParentLocale(locale));

        // Locale with script and country
        locale = new Locale.Builder()
                .setLanguage(Locale.CHINA.getLanguage())
                .setScript(script)
                .setRegion(Locale.CHINA.getCountry())
                .build();
        Assert.assertEquals(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setScript(script)
                        .build(),
                provider.getParentLocale(locale));

        // Locale with script only
        locale = new Locale.Builder()
                .setLanguage(Locale.CHINA.getLanguage())
                .setScript(script)
                .build();
        Assert.assertEquals(new Locale(Locale.CHINA.getLanguage()), provider.getParentLocale(locale));

        // Locale with variant only
        locale = new Locale(Locale.CHINA.getLanguage(), Locale.CHINA.getCountry(), variant);
        Assert.assertEquals(
                new Locale(Locale.CHINA.getLanguage(), Locale.CHINA.getCountry()), provider.getParentLocale(locale));

        // Locale with country only
        locale = new Locale(Locale.CHINA.getLanguage(), Locale.CHINA.getCountry());
        Assert.assertEquals(new Locale(Locale.CHINA.getLanguage()), provider.getParentLocale(locale));

        // Locale with language only
        locale = new Locale(Locale.CHINA.getLanguage());
        Assert.assertEquals(provider.getDefaultLocale(), provider.getParentLocale(locale));

        // The parent of the default locale is null
        Assert.assertNull(provider.getParentLocale(provider.getDefaultLocale()));
    }
}
