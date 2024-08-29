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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.i18n.impl.JcrResourceBundleProvider.Key;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;

/**
 * Test case to verify that each bundle is only loaded once, even
 * if concurrent requests for the same bundle are made.
 */
@RunWith(Parameterized.class)
public class ConcurrentJcrResourceBundleLoadingTest {

    @Rule
    public final SlingContext context = new SlingContext();

    @Parameterized.Parameters(name = "preload_bundles={0}")
    public static Iterable<? extends Object> PRELOAD_BUNDLES() {
        return Arrays.asList(Boolean.TRUE, Boolean.FALSE);
    }

    @Parameterized.Parameter
    public Boolean preload = Boolean.FALSE;

    private JcrResourceBundleProvider provider;

    @Before
    public void setup() throws Exception {
        // mock other required services
        Scheduler mockScheduler = context.registerService(Scheduler.class, Mockito.mock(Scheduler.class));
        // mock this call to avoid a NPE during activation
        Mockito.doAnswer(invocation -> {
                    return Mockito.mock(ScheduleOptions.class);
                })
                .when(mockScheduler)
                .NOW();
        // Mock the schedule call so we do not wait for the "ResourceBundleProvider: reload all resource bundles"
        //   scheduled job to be completed during activation.  That background schedule execution can interfere with
        //   the multi-threaded tests (i.e. the cache gets reset in the middle of doing something)
        Mockito.doAnswer(invocation -> {
                    Runnable runnable = invocation.getArgument(0, Runnable.class);
                    runnable.run();
                    return null;
                })
                .when(mockScheduler)
                .schedule(any(Runnable.class), any(ScheduleOptions.class));
        context.registerService(ServiceUserMapped.class, Mockito.mock(ServiceUserMapped.class));

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("preload.bundles", preload);
        configMap.put("locale.default", "en");
        configMap.put("invalidation.delay", 5000);
        configMap.put("included.paths", new String[] {"/libs", "/apps"});
        configMap.put("excluded.paths", new String[] {"/var/eventing"});
        provider = context.registerInjectActivateService(JcrResourceBundleProvider.class, configMap);
    }

    @Test
    public void loadBundlesOnlyOncePerLocale() throws Exception {
        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        ResourceBundle german = provider.getResourceBundle(Locale.GERMAN);
        assertEquals(english, provider.getResourceBundle(Locale.ENGLISH));
        assertEquals(english, provider.getResourceBundle(Locale.ENGLISH));
        assertEquals(german, provider.getResourceBundle(Locale.GERMAN));
        assertEquals(german, provider.getResourceBundle(Locale.GERMAN));
    }

    @Test
    public void loadBundlesOnlyOnceWithConcurrentRequests() throws Exception {
        Map<Locale, List<ResourceBundle>> rbMap = new ConcurrentHashMap<>();
        final int numberOfThreads = 40;
        final ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads / 2);
        for (int i = 0; i < numberOfThreads; i++) {
            final Locale language = i < numberOfThreads / 2 ? Locale.ENGLISH : Locale.GERMAN;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    ResourceBundle rb = provider.getResourceBundle(language);
                    List<ResourceBundle> list = rbMap.computeIfAbsent(language, key -> new ArrayList<>());
                    list.add(rb);
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // convert to set to remove the duplicates
        Set<ResourceBundle> englishSet = new HashSet<>(rbMap.get(Locale.ENGLISH));
        // should only be one uqique value
        assertEquals(1, englishSet.size());
        Set<ResourceBundle> germanSet = new HashSet<>(rbMap.get(Locale.GERMAN));
        assertEquals(1, germanSet.size());
    }

    @Test
    public void newBundleUsedAfterReload() throws Exception {
        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        ResourceBundle german = provider.getResourceBundle(Locale.GERMAN);

        // reloading german should not reload any other bundle
        provider.reloadBundle(new Key(null, Locale.GERMAN));
        assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
        ResourceBundle german2 = provider.getResourceBundle(Locale.GERMAN);
        assertNotSame(german2, german);
        assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
        assertSame(german2, provider.getResourceBundle(Locale.GERMAN));
        assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
        assertSame(german2, provider.getResourceBundle(Locale.GERMAN));
    }

    @Test
    public void newBundleUsedAsParentAfterReload() throws Exception {
        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        ResourceBundle german = provider.getResourceBundle(Locale.GERMAN);

        // reloading english should also reload german (because it has english as a parent)
        provider.reloadBundle(new Key(null, Locale.ENGLISH));
        ResourceBundle english2 = provider.getResourceBundle(Locale.ENGLISH);
        assertNotSame(english2, english);
        ResourceBundle german2 = provider.getResourceBundle(Locale.GERMAN);
        assertNotSame(german2, german);
        assertSame(english2, provider.getResourceBundle(Locale.ENGLISH));
        assertSame(german2, provider.getResourceBundle(Locale.GERMAN));
        assertSame(english2, provider.getResourceBundle(Locale.ENGLISH));
        assertSame(german2, provider.getResourceBundle(Locale.GERMAN));
    }

    /**
     * Test that when a ResourceBundle is reloaded the already cached ResourceBundle is returned (preload=true) as long as a long running
     * call to createResourceBundle() takes. For preload=false that will be blocking in getResourceBundle() instead.
     *
     * @throws Exception
     */
    @Test
    public void newBundleReplacesOldBundleAfterReload() throws Exception {
        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        final CountDownLatch newBundleReturned = new CountDownLatch(1);

        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
        executorService.scheduleAtFixedRate(
                () -> {
                    ResourceBundle currentBundle = provider.getResourceBundle(Locale.ENGLISH);
                    if (currentBundle != english) {
                        // Shutdown the executor once we got the new ResourceBundle. This will cancel the future and
                        // opens the gate below.
                        // Do not assert the returned bundle directly here, as this may become flaky when the bundle
                        // returned by the
                        // mock above was returned but not yet put into the cache.
                        executorService.shutdownNow();
                        newBundleReturned.countDown();
                    }
                },
                // start with an initial delay to not call getResourceBundle again before we call reloadBundle below
                200,
                200,
                TimeUnit.MILLISECONDS);

        provider.reloadBundle(new Key(null, Locale.ENGLISH));

        // wait until the scheduled future gets canceled by shutting down the executor service
        // this means the new bundle got returned and is in the cache
        if (!newBundleReturned.await(5000, TimeUnit.MILLISECONDS)) {
            fail("expected cancellation");
        }
        // CancellationException expected

        ResourceBundle english2 = provider.getResourceBundle(Locale.ENGLISH);
        assertNotSame(english2, english);
    }

    /**
     * Verify that no exception occurs if requests come in during deactivate
     */
    @Test
    public void loadBundlesDuringDeactivateRace() {
        provider.deactivate();
        assertNotNull(provider.getResourceBundle(Locale.ENGLISH));
    }

    /**
     * Verify that the registry is cleared completely and all services are deregistered
     * so no service is leftover even if registering and clearing occur in an interleaved manner.
     */
    @Test
    public void clearCacheInterleavedWithRegistersClearsAllRBs() throws Exception {
        Map<Locale, List<ResourceBundle>> rbLists = new ConcurrentHashMap<>();
        final Locale[] testLocales = {
            Locale.ENGLISH,
            Locale.FRENCH,
            Locale.GERMAN,
            Locale.ITALIAN,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.CHINESE,
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE
        };

        final int numberOfThreads = 100;
        // Use a barrier to start the execution of all the threads simultaneously once they are all ready
        final CyclicBarrier barrier = new CyclicBarrier(numberOfThreads);
        final ExecutorService pool = Executors.newFixedThreadPool(numberOfThreads);
        for (int i = 0; i < numberOfThreads; i++) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        for (Locale locale : testLocales) {
                            ResourceBundle rb = provider.getResourceBundle(locale);
                            List<ResourceBundle> rbList = rbLists.computeIfAbsent(locale, key -> new ArrayList<>());
                            rbList.add(rb);
                        }
                        // trigger registry clearing and service deregistration
                        provider.clearCache();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // getResourceBundle should return a new ResourceBundle instance,
        // not one that might be left in the cache by an incomplete clearing
        for (Locale locale : testLocales) {
            ResourceBundle rb = provider.getResourceBundle(locale);
            List<ResourceBundle> rbList = rbLists.get(locale);
            assertFalse(rbList.contains(rb));
        }
    }
}
