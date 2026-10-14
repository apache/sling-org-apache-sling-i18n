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
import java.util.Collections;
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
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
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
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;

/**
 * Verifies bundle caching, concurrent loading, and refreshed fallback chains
 * with both eager preloading and lazy invalidation.
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
    private ResourceResolverFactory resolverFactory;
    private final List<Resource> dictionaryRoots = new ArrayList<>();

    @Before
    public void setup() throws Exception {
        ResourceResolver resolver = Mockito.spy(context.resourceResolver());
        Mockito.doNothing().when(resolver).close();
        Mockito.doAnswer(query -> dictionaryRoots.iterator())
                .when(resolver)
                .findResources(JcrResourceBundle.QUERY_LANGUAGE_ROOTS, "xpath");
        resolverFactory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(resolverFactory.getServiceResourceResolver(null)).thenReturn(resolver);
        context.registerService(ResourceResolverFactory.class, resolverFactory, Constants.SERVICE_RANKING, 1000);
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

    /**
     * Reloading a child must preserve its unchanged parent, both in the cache
     * and in the child's fallback reference.
     */
    @Test
    public void newBundleUsedAfterReload() throws Exception {
        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        ResourceBundle german = provider.getResourceBundle(Locale.GERMAN);

        // reloading german should not reload any other bundle
        provider.reloadBundle(new Key(null, Locale.GERMAN));
        assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
        ResourceBundle german2 = provider.getResourceBundle(Locale.GERMAN);
        assertNotSame(german2, german);
        assertSame(english, ((JcrResourceBundle) german2).getParent());
        assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
        assertSame(german2, provider.getResourceBundle(Locale.GERMAN));
        assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
        assertSame(german2, provider.getResourceBundle(Locale.GERMAN));
    }

    /**
     * Changes an English fallback shared by the de -> en, de_DE -> de -> en,
     * and fr -> en chains. Refreshed children must reference the current parents
     * and return the new fallback while retaining locale-specific overrides.
     * An explicitly unnamed reload must leave the named bundle chain untouched.
     */
    @Test
    public void reloadedFallbacksUseCurrentParents() {
        Resource englishDictionary = createDictionary("en", "en");
        Resource fallback =
                context.create().resource(englishDictionary.getPath() + "/fallback", "sling:message", "old English");
        Resource germanDictionary = createDictionary("de", "de");
        context.create().resource(germanDictionary.getPath() + "/override", "sling:message", "German override");
        Resource countryDictionary = createDictionary("de_DE", "de_DE");
        context.create().resource(countryDictionary.getPath() + "/override", "sling:message", "Country override");
        Resource namedDictionary = createDictionary("named-en", "en", "named");
        context.create().resource(namedDictionary.getPath() + "/named", "sling:message", "Named English");

        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        ResourceBundle german = provider.getResourceBundle(Locale.GERMAN);
        ResourceBundle country = provider.getResourceBundle(Locale.GERMANY);
        ResourceBundle french = provider.getResourceBundle(Locale.FRENCH);
        ResourceBundle named = provider.getResourceBundle("named", Locale.GERMANY);
        assertEquals("old English", country.getString("fallback"));
        assertEquals("old English", french.getString("fallback"));

        fallback.adaptTo(ModifiableValueMap.class).put("sling:message", "new English");
        provider.reloadBundle(new Key(null, Locale.ENGLISH));

        ResourceBundle english2 = provider.getResourceBundle(Locale.ENGLISH);
        ResourceBundle german2 = provider.getResourceBundle(Locale.GERMAN);
        ResourceBundle country2 = provider.getResourceBundle(Locale.GERMANY);
        ResourceBundle french2 = provider.getResourceBundle(Locale.FRENCH);
        assertNotSame(english, english2);
        assertNotSame(german, german2);
        assertNotSame(country, country2);
        assertNotSame(french, french2);
        assertSame(english2, ((JcrResourceBundle) german2).getParent());
        assertSame(german2, ((JcrResourceBundle) country2).getParent());
        assertSame(english2, ((JcrResourceBundle) french2).getParent());
        assertEquals("new English", country2.getString("fallback"));
        assertEquals("new English", french2.getString("fallback"));
        assertEquals("German override", german2.getString("override"));
        assertEquals("Country override", country2.getString("override"));
        assertSame(named, provider.getResourceBundle("named", Locale.GERMANY));
        assertEquals("Named English", named.getString("named"));
    }

    /**
     * Refreshes the named en -> de -> de_DE dependency chain and verifies its
     * updated fallback value and parent references. Explicitly refreshing that
     * basename must not replace the cached unnamed bundles.
     */
    @Test
    public void namedFallbackRefreshDoesNotReplaceOtherBasenames() {
        Resource dictionary = createDictionary("named-en", "en", "named");
        Resource message =
                context.create().resource(dictionary.getPath() + "/fallback", "sling:message", "old named English");
        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        ResourceBundle german = provider.getResourceBundle(Locale.GERMAN);
        ResourceBundle named = provider.getResourceBundle("named", Locale.GERMANY);
        assertEquals("old named English", named.getString("fallback"));

        message.adaptTo(ModifiableValueMap.class).put("sling:message", "new named English");
        provider.reloadBundle(new Key("named", Locale.ENGLISH));

        ResourceBundle named2 = provider.getResourceBundle("named", Locale.GERMANY);
        JcrResourceBundle namedGerman = (JcrResourceBundle) provider.getResourceBundle("named", Locale.GERMAN);
        assertNotSame(named, named2);
        assertSame(namedGerman, ((JcrResourceBundle) named2).getParent());
        assertSame(provider.getResourceBundle("named", Locale.ENGLISH), namedGerman.getParent());
        assertEquals("new named English", named2.getString("fallback"));
        assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
        assertSame(german, provider.getResourceBundle(Locale.GERMAN));
    }

    private Resource createDictionary(String name, String language, String... baseNames) {
        Resource dictionary = context.create()
                .resource(
                        "/apps/i18n/" + name,
                        "jcr:mixinTypes",
                        new String[] {"mix:language"},
                        "jcr:language",
                        language,
                        "sling:basename",
                        baseNames);
        dictionaryRoots.add(dictionary);
        return dictionary;
    }

    /**
     * Replacing a parent must also replace its dependent child and attach the
     * child to the new parent. New bundle identities alone would miss a stale
     * parent reference left by child-first eager refresh.
     */
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
        assertSame(english2, ((JcrResourceBundle) german2).getParent());
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
    public void loadBundlesDuringDeactivateRace() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ResourceResolver resolver = resolverFactory.getServiceResourceResolver(null);
        Mockito.when(resolverFactory.getServiceResourceResolver(null)).thenAnswer(invocation -> {
            loading.countDown();
            await(resume);
            return resolver;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResourceBundle> lookup = executor.submit(() -> provider.getResourceBundle(Locale.ENGLISH));
            assertTrue(loading.await(10, TimeUnit.SECONDS));
            provider.deactivate();
            assertNoResourceBundleServices();
            resume.countDown();
            assertNotNull(lookup.get(10, TimeUnit.SECONDS));
            assertNotNull(provider.getResourceBundle(Locale.ENGLISH));
            assertNoResourceBundleServices();
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void registrationDuringDeactivateDoesNotLeaveServices() throws Exception {
        assertRegistrationDuringDeactivate(false);
    }

    @Test
    public void replacementDuringDeactivateDoesNotLeaveServices() throws Exception {
        assertRegistrationDuringDeactivate(true);
    }

    private void assertRegistrationDuringDeactivate(boolean replacement) throws Exception {
        if (replacement) {
            provider.getResourceBundle(Locale.ENGLISH);
        }
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Map<Long, Integer> unregistrations = new ConcurrentHashMap<>();
        ServiceListener listener = event -> {
            Long serviceId = (Long) event.getServiceReference().getProperty(Constants.SERVICE_ID);
            if (event.getType() == ServiceEvent.REGISTERED) {
                registering.countDown();
                await(resume);
            } else if (event.getType() == ServiceEvent.UNREGISTERING) {
                unregistrations.merge(serviceId, 1, Integer::sum);
            }
        };
        context.bundleContext().addServiceListener(listener, "(&(objectClass=java.util.ResourceBundle)(locale=en))");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResourceBundle> lookup = executor.submit(() -> {
                if (replacement) {
                    provider.reloadBundle(new Key(null, Locale.ENGLISH));
                }
                return provider.getResourceBundle(Locale.ENGLISH);
            });
            assertTrue(registering.await(10, TimeUnit.SECONDS));
            provider.deactivate();
            resume.countDown();
            assertNotNull(lookup.get(10, TimeUnit.SECONDS));
            assertNoResourceBundleServices();
            assertEquals(replacement ? 2 : 1, unregistrations.size());
            unregistrations.values().forEach(count -> assertEquals(Integer.valueOf(1), count));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            context.bundleContext().removeServiceListener(listener);
        }
    }

    @Test
    public void deactivateFromRegistrationCallbackDoesNotLeaveServices() throws Exception {
        ServiceListener listener = event -> {
            if (event.getType() == ServiceEvent.REGISTERED) {
                provider.deactivate();
            }
        };
        context.bundleContext().addServiceListener(listener, "(objectClass=java.util.ResourceBundle)");
        try {
            assertNotNull(provider.getResourceBundle(Locale.ENGLISH));
            assertNoResourceBundleServices();
        } finally {
            context.bundleContext().removeServiceListener(listener);
        }
    }

    @Test
    public void replacementRetirementDuringDeactivateUnregistersEachServiceOnce() throws Exception {
        provider.getResourceBundle(Locale.ENGLISH);
        Long originalId = (Long) context.bundleContext()
                .getServiceReference(ResourceBundle.class)
                .getProperty(Constants.SERVICE_ID);
        CountDownLatch retiring = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Map<Long, Integer> unregistrations = new ConcurrentHashMap<>();
        ServiceListener listener = event -> {
            if (event.getType() == ServiceEvent.UNREGISTERING) {
                Long serviceId = (Long) event.getServiceReference().getProperty(Constants.SERVICE_ID);
                unregistrations.merge(serviceId, 1, Integer::sum);
                if (originalId.equals(serviceId)) {
                    retiring.countDown();
                    await(resume);
                }
            }
        };
        context.bundleContext().addServiceListener(listener, "(objectClass=java.util.ResourceBundle)");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> reload = executor.submit(() -> provider.reloadBundle(new Key(null, Locale.ENGLISH)));
            assertTrue(retiring.await(10, TimeUnit.SECONDS));
            provider.deactivate();
            resume.countDown();
            reload.get(10, TimeUnit.SECONDS);
            assertNoResourceBundleServices();
            assertEquals(preload ? 2 : 1, unregistrations.size());
            unregistrations.values().forEach(count -> assertEquals(Integer.valueOf(1), count));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            context.bundleContext().removeServiceListener(listener);
        }
    }

    private void assertNoResourceBundleServices() throws Exception {
        assertTrue(context.bundleContext()
                .getServiceReferences(ResourceBundle.class, null)
                .isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the test gate", e);
        }
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
        final List<Future<?>> tasks = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i++) {
            tasks.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        for (Locale locale : testLocales) {
                            ResourceBundle rb = provider.getResourceBundle(locale);
                            List<ResourceBundle> rbList = rbLists.computeIfAbsent(
                                    locale, key -> Collections.synchronizedList(new ArrayList<>()));
                            rbList.add(rb);
                        }
                        // trigger registry clearing and service deregistration
                        provider.clearCache();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
            assertNoResourceBundleServices();
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // getResourceBundle should return a new ResourceBundle instance,
        // not one that might be left in the cache by an incomplete clearing
        for (Locale locale : testLocales) {
            ResourceBundle rb = provider.getResourceBundle(locale);
            List<ResourceBundle> rbList = rbLists.get(locale);
            assertFalse(rbList.contains(rb));
        }
    }
}
