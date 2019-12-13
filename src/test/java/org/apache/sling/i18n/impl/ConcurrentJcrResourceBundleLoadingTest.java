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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.powermock.api.mockito.PowerMockito.doAnswer;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.spy;
import static org.powermock.api.mockito.PowerMockito.verifyPrivate;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.i18n.impl.JcrResourceBundleProvider.Key;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationMode;
import org.osgi.framework.BundleContext;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test case to verify that each bundle is only loaded once, even
 * if concurrent requests for the same bundle are made.
 */
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(Parameterized.class)
@PrepareForTest(JcrResourceBundleProvider.class)
public class ConcurrentJcrResourceBundleLoadingTest {

    @Parameterized.Parameters(name = "preload_bundles={0}")
    public static Iterable<? extends Object> PRELOAD_BUNDLES() {
        return Arrays.asList(Boolean.TRUE, Boolean.FALSE);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrentJcrResourceBundleLoadingTest.class);

    @Mock JcrResourceBundle english;
    @Mock JcrResourceBundle german;

    @Parameterized.Parameter public Boolean preload = Boolean.FALSE;

    private JcrResourceBundleProvider provider;

    @Before
    public void setup() throws Exception {
        provider = spy(new JcrResourceBundleProvider());
        provider.activate(PowerMockito.mock(BundleContext.class), new JcrResourceBundleProvider.Config() {

            @Override
            public Class<? extends Annotation> annotationType() {
                return JcrResourceBundleProvider.Config.class;
            }

            @Override
            public boolean preload_bundles() {
                return preload;
            }

            @Override
            public String locale_default() {
                return "en";
            }

            @Override
            public long invalidation_delay() {
                return 5000;
            }
        });
        doReturn(null).when(provider, "createResourceResolver");
        doReturn(english).when(provider, "createResourceBundle", any(ResourceResolver.class), eq(null), eq(Locale.ENGLISH));
        doReturn(german).when(provider, "createResourceBundle", any(ResourceResolver.class), eq(null), eq(Locale.GERMAN));
        Mockito.when(german.getLocale()).thenReturn(Locale.GERMAN);
        Mockito.when(english.getLocale()).thenReturn(Locale.ENGLISH);
        Mockito.when(german.getParent()).thenReturn(english);
    }

    @Test
    public void loadBundlesOnlyOncePerLocale() throws Exception {
        assertEquals(english, provider.getResourceBundle(Locale.ENGLISH));
        assertEquals(english, provider.getResourceBundle(Locale.ENGLISH));
        assertEquals(german, provider.getResourceBundle(Locale.GERMAN));
        assertEquals(german, provider.getResourceBundle(Locale.GERMAN));

        verifyPrivate(provider, times(2)).invoke("createResourceBundle", any(ResourceResolver.class), eq(null), any(Locale.class));
    }

    @Test
    public void loadBundlesOnlyOnceWithConcurrentRequests() throws Exception {
        final int numberOfThreads = 40;
        final ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads / 2);
        for (int i = 0; i < numberOfThreads; i++) {
            final Locale language = i < numberOfThreads / 2 ? Locale.ENGLISH : Locale.GERMAN;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    provider.getResourceBundle(language);
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        verifyPrivate(provider, times(1)).invoke("createResourceBundle", any(ResourceResolver.class), eq(null), eq(Locale.ENGLISH));
        verifyPrivate(provider, times(1)).invoke("createResourceBundle", any(ResourceResolver.class), eq(null), eq(Locale.GERMAN));
    }

    @Test
    public void newBundleUsedAfterReload() throws Exception {
        provider.getResourceBundle(Locale.ENGLISH);
        provider.getResourceBundle(Locale.GERMAN);

        // reloading german should not reload any other bundle
        provider.reloadBundle(new Key(null, Locale.GERMAN));
        provider.getResourceBundle(Locale.ENGLISH);
        provider.getResourceBundle(Locale.GERMAN);
        provider.getResourceBundle(Locale.ENGLISH);
        provider.getResourceBundle(Locale.GERMAN);
        provider.getResourceBundle(Locale.ENGLISH);
        provider.getResourceBundle(Locale.GERMAN);

        verifyPrivate(provider, times(1)).invoke("createResourceBundle", any(ResourceResolver.class), eq(null), eq(Locale.ENGLISH));
        verifyPrivate(provider, times(2)).invoke("createResourceBundle", any(ResourceResolver.class), eq(null), eq(Locale.GERMAN));
    }

    @Test
    public void newBundleUsedAsParentAfterReload() throws Exception {
        provider.getResourceBundle(Locale.ENGLISH);
        provider.getResourceBundle(Locale.GERMAN);

        // reloading english should also reload german (because it has english as a parent)
        provider.reloadBundle(new Key(null, Locale.ENGLISH));
        provider.getResourceBundle(Locale.ENGLISH);
        provider.getResourceBundle(Locale.GERMAN);
        provider.getResourceBundle(Locale.ENGLISH);
        provider.getResourceBundle(Locale.GERMAN);
        provider.getResourceBundle(Locale.ENGLISH);
        provider.getResourceBundle(Locale.GERMAN);

        verifyPrivate(provider, times(2)).invoke("createResourceBundle", any(ResourceResolver.class), eq(null), eq(Locale.ENGLISH));
        verifyPrivate(provider, times(2)).invoke("createResourceBundle", any(ResourceResolver.class), eq(null), eq(Locale.GERMAN));
    }

    /**
     * Test that when a ResourceBundle is reloaded the already cached ResourceBundle is returned (preload=true) as long as a long running
     * call to createResourceBundle() takes. For preload=false that will be blocking in getResourceBundle() instead.
     *
     * @throws Exception
     */
    @Test
    public void newBundleReplacesOldBundleAfterReload() throws Exception {
        final ResourceBundle oldBundle = provider.getResourceBundle(Locale.ENGLISH);
        final ResourceBundle newBundle = mock(JcrResourceBundle.class);
        final AtomicBoolean newBundleReady = new AtomicBoolean(false);

        doAnswer(new Answer<ResourceBundle>() {
            @Override public ResourceBundle answer(InvocationOnMock invocationOnMock) throws Throwable {
                Thread.sleep(1000);
                newBundleReady.set(true);
                return newBundle;
            }
        }).when(provider, "createResourceBundle", any(ResourceResolver.class), eq(null), eq(Locale.ENGLISH));

        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                ResourceBundle expected = newBundleReady.get() ? newBundle : oldBundle;
                assertSame(expected, provider.getResourceBundle(Locale.ENGLISH));
            }
        }, 0, 200, TimeUnit.MILLISECONDS);

        provider.reloadBundle(new Key(null, Locale.ENGLISH));

        // we expect getResourceBundleInternal() called once in the beginning of the test and once again after reloading the bundle.
        final int expectedGetResourceBundleInternal = 2;
        VerificationMode verificationMode;

        if (preload) {
            // when preloading the calls to getResourceBundleInternal are non-blocking and so more calls will happen while reloading.
            // assuming at least one more
            verificationMode = atLeast(expectedGetResourceBundleInternal + 1);
        } else {
            verificationMode = times(expectedGetResourceBundleInternal);
        }

        verifyPrivate(provider, verificationMode)
                .invoke("getResourceBundleInternal", any(ResourceResolver.class), eq(null), eq(Locale.ENGLISH), anyBoolean());
    }
}