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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * Covers locale handling and observation-driven refresh with eager and lazy loading.
 * Observation tests explicitly run queued work, then check translations, dependent
 * bundle references, and preservation of unrelated cached bundles.
 */
@RunWith(Parameterized.class)
public class JcrResourceBundleProviderTest {

    private static final String KEY = "checkout-label";
    private static final String[] BASENAMES = {null, "checkout", "store", "other"};
    private static final String RELOAD_ALL = "ResourceBundleProvider: reload all resource bundles";

    @Rule
    public final SlingContext context = new SlingContext();

    @Parameterized.Parameters(name = "preload_bundles={0}")
    public static Iterable<Boolean> preloadBundles() {
        return Arrays.asList(Boolean.FALSE, Boolean.TRUE);
    }

    @Parameterized.Parameter
    public boolean preload;

    private JcrResourceBundleProvider provider;
    private ResourceResolverFactory factory;
    private final List<String> dictionaryPaths = new ArrayList<>();
    private final Map<String, Runnable> scheduledJobs = new LinkedHashMap<>();
    private String scheduledJobName;

    private void setupObservation() throws Exception {
        Resource english = createDictionary("/apps/i18n/en", "en", "checkout", "store", "other");
        createEntry(english.getPath() + "/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "English");
        createDictionary("/apps/i18n/de", "de");
        createDictionary("/apps/i18n/fr", "fr");

        ResourceResolver resolver = Mockito.spy(context.resourceResolver());
        Mockito.doNothing().when(resolver).close();
        Mockito.doAnswer(query -> dictionaries().iterator())
                .when(resolver)
                .findResources(JcrResourceBundle.QUERY_LANGUAGE_ROOTS, "xpath");
        Mockito.doAnswer(query -> dictionaries().stream()
                        .map(root -> {
                            Map<String, Object> row = new HashMap<>(root.getValueMap());
                            row.put("jcr:path", root.getPath());
                            return row;
                        })
                        .iterator())
                .when(resolver)
                .queryResources(JcrResourceBundle.QUERY_LANGUAGE_ROOTS, "xpath");
        factory = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(factory.getServiceResourceResolver(null)).thenReturn(resolver);
        context.registerService(ResourceResolverFactory.class, factory, Constants.SERVICE_RANKING, 1000);
        context.registerService(ServiceUserMapped.class, Mockito.mock(ServiceUserMapped.class));

        Scheduler scheduler = context.registerService(Scheduler.class, Mockito.mock(Scheduler.class));
        ScheduleOptions options = Mockito.mock(ScheduleOptions.class);
        Mockito.when(scheduler.NOW()).thenReturn(options);
        Mockito.when(scheduler.AT(any(Date.class))).thenReturn(options);
        Mockito.doAnswer(invocation -> {
                    scheduledJobName = invocation.getArgument(0);
                    return options;
                })
                .when(options)
                .name(any(String.class));
        Mockito.doAnswer(invocation -> {
                    scheduledJobs.put(scheduledJobName, invocation.getArgument(0));
                    return true;
                })
                .when(scheduler)
                .schedule(any(Runnable.class), any(ScheduleOptions.class));
        Mockito.doAnswer(invocation -> scheduledJobs.remove(invocation.getArgument(0)) != null)
                .when(scheduler)
                .unschedule(any(String.class));

        Map<String, Object> config = new HashMap<>();
        config.put("preload.bundles", preload);
        config.put("locale.default", "en");
        config.put("included.paths", new String[] {"/apps", "/libs"});
        config.put("excluded.paths", new String[] {"/apps/excluded", "/apps/i18n/de/excluded"});
        provider = context.registerInjectActivateService(JcrResourceBundleProvider.class, config);
        runScheduledWork();
        cacheBundles();
    }

    private Resource createDictionary(String path, String language, String... basenames) {
        Resource root = context.create()
                .resource(
                        path,
                        "jcr:primaryType",
                        "nt:unstructured",
                        "jcr:mixinTypes",
                        new String[] {JcrResourceBundle.MIXIN_LANGUAGE},
                        "jcr:language",
                        language,
                        "sling:basename",
                        basenames);
        dictionaryPaths.add(path);
        return root;
    }

    private List<Resource> dictionaries() {
        return dictionaryPaths.stream()
                .map(path -> context.resourceResolver().getResource(path))
                .filter(root -> root != null
                        && root.getValueMap().containsKey("jcr:language")
                        && Arrays.asList(root.getValueMap().get("jcr:mixinTypes", new String[0]))
                                .contains(JcrResourceBundle.MIXIN_LANGUAGE))
                .collect(Collectors.toList());
    }

    private Resource createEntry(String path, String type, String message) {
        return context.create().resource(path, "jcr:primaryType", type, "sling:key", KEY, "sling:message", message);
    }

    private Map<String, List<ResourceBundle>> cacheBundles() {
        Map<String, List<ResourceBundle>> bundles = new LinkedHashMap<>();
        for (String basename : BASENAMES) {
            bundles.put(
                    basename,
                    Arrays.asList(
                            provider.getResourceBundle(basename, Locale.ENGLISH),
                            provider.getResourceBundle(basename, Locale.GERMAN),
                            provider.getResourceBundle(basename, Locale.GERMANY),
                            provider.getResourceBundle(basename, Locale.FRENCH)));
        }
        return bundles;
    }

    private void runScheduledWork() {
        List<Runnable> jobs = new ArrayList<>(scheduledJobs.values());
        scheduledJobs.clear();
        jobs.forEach(Runnable::run);
    }

    private void assertTargetedChange(String path, ChangeType type, String expected, String... affectedBasenames) {
        Map<String, List<ResourceBundle>> before = cacheBundles();
        provider.onChange(Collections.singletonList(new ResourceChange(type, path, false)));
        assertEquals(affectedBasenames.length, scheduledJobs.size());
        assertFalse("Entry edits must not schedule rediscovery", scheduledJobs.containsKey(RELOAD_ALL));
        Map<String, List<ResourceBundle>> pending = cacheBundles();
        before.forEach((basename, bundles) -> {
            for (int i = 0; i < bundles.size(); i++) {
                assertSame(
                        "Changes must wait for scheduled work",
                        bundles.get(i),
                        pending.get(basename).get(i));
            }
        });
        runScheduledWork();
        Map<String, List<ResourceBundle>> after = cacheBundles();
        before.forEach((basename, bundles) -> {
            boolean affected = Arrays.asList(affectedBasenames).contains(basename);
            List<ResourceBundle> refreshed = after.get(basename);
            assertSame("English fallback is unchanged", bundles.get(0), refreshed.get(0));
            assertSame("French is unrelated", bundles.get(3), refreshed.get(3));
            if (affected) {
                assertNotSame(bundles.get(1), refreshed.get(1));
                assertNotSame("Dependent country bundle must refresh", bundles.get(2), refreshed.get(2));
                assertSame(refreshed.get(1), ((JcrResourceBundle) refreshed.get(2)).getParent());
                assertEquals(expected, refreshed.get(1).getString(KEY));
                assertEquals(expected, refreshed.get(2).getString(KEY));
            } else {
                assertSame("Unrelated basename is unchanged", bundles.get(1), refreshed.get(1));
                assertSame(bundles.get(2), refreshed.get(2));
            }
        });
    }

    private void assertFullReload(ResourceChange... changes) {
        Map<String, List<ResourceBundle>> before = cacheBundles();
        provider.onChange(Arrays.asList(changes));
        assertEquals(Collections.singleton(RELOAD_ALL), scheduledJobs.keySet());
        runScheduledWork();
        Map<String, List<ResourceBundle>> after = cacheBundles();
        before.forEach((basename, bundles) -> {
            for (int i = 0; i < bundles.size(); i++) {
                assertNotSame(
                        "Discovery changes must invalidate every bundle",
                        bundles.get(i),
                        after.get(basename).get(i));
            }
        });
    }

    private void assertIgnoredChange(ResourceChange... changes) {
        Map<String, List<ResourceBundle>> before = cacheBundles();
        provider.onChange(Arrays.asList(changes));
        assertTrue(scheduledJobs.isEmpty());
        runScheduledWork();
        Map<String, List<ResourceBundle>> after = cacheBundles();
        before.forEach((basename, bundles) -> {
            for (int i = 0; i < bundles.size(); i++) {
                assertSame(bundles.get(i), after.get(basename).get(i));
            }
        });
    }

    /**
     * Pauses an eager full refresh during construction and verifies that cached lookups and old services
     * remain available. After publication, checks fresh English fallback references and translations,
     * registration-before-retirement ordering, and preservation of old bundle objects as snapshots.
     */
    @Test
    public void fullReloadKeepsOldServicesUntilFreshFallbacksAreReady() throws Exception {
        assumeTrue(preload);
        setupObservation();
        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        ResourceBundle german = provider.getResourceBundle(Locale.GERMAN);
        Set<ServiceReference<ResourceBundle>> previousServices = bundleServices();
        context.resourceResolver()
                .getResource("/apps/i18n/en/" + KEY)
                .adaptTo(ModifiableValueMap.class)
                .put("sling:message", "Updated English");
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseNextDictionaryLoad(loading, resume);
        List<String> fallbacksDuringRetirement = new ArrayList<>();
        List<Integer> serviceEvents = new ArrayList<>();
        ServiceListener listener = event -> {
            serviceEvents.add(event.getType());
            if (event.getType() == ServiceEvent.UNREGISTERING) {
                fallbacksDuringRetirement.add(
                        provider.getResourceBundle(Locale.GERMAN).getString(KEY));
            }
        };
        context.bundleContext().addServiceListener(listener, "(objectClass=java.util.ResourceBundle)");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> reload = executor.submit(provider::reloadAllBundles);
            assertTrue(loading.await(10, TimeUnit.SECONDS));
            assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
            assertSame(german, provider.getResourceBundle(Locale.GERMAN));
            assertEquals("English", german.getString(KEY));
            assertEquals(previousServices, bundleServices());
            resume.countDown();
            reload.get(10, TimeUnit.SECONDS);
            ResourceBundle replacement = provider.getResourceBundle(Locale.GERMAN);
            assertNotSame(german, replacement);
            assertEquals("Updated English", replacement.getString(KEY));
            assertSame(provider.getResourceBundle(Locale.ENGLISH), ((JcrResourceBundle) replacement).getParent());
            assertEquals("English", german.getString(KEY));
            assertFalse(fallbacksDuringRetirement.isEmpty());
            assertTrue(fallbacksDuringRetirement.stream().allMatch("Updated English"::equals));
            assertTrue(serviceEvents.lastIndexOf(ServiceEvent.REGISTERED)
                    < serviceEvents.indexOf(ServiceEvent.UNREGISTERING));
            for (ServiceReference<ResourceBundle> reference : previousServices) {
                assertFalse(bundleServices().contains(reference));
            }
            assertEquals(
                    "Updated English",
                    provider.getResourceBundle("checkout", Locale.GERMAN).getString(KEY));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            context.bundleContext().removeServiceListener(listener);
        }
    }

    /**
     * Changes a dictionary's locale, removes a basename, and deletes another dictionary.
     * A full refresh must retire obsolete services and forget the deleted language root,
     * while making the new locale available with its English fallback.
     */
    @Test
    public void fullReloadRetiresRemovedOwnershipAndLanguageRoots() throws Exception {
        assumeTrue(preload);
        setupObservation();
        Resource german = context.resourceResolver().getResource("/apps/i18n/de");
        german.adaptTo(ModifiableValueMap.class).put("jcr:language", "it");
        context.resourceResolver()
                .getResource("/apps/i18n/en")
                .adaptTo(ModifiableValueMap.class)
                .put("sling:basename", new String[] {"checkout"});
        context.resourceResolver().delete(context.resourceResolver().getResource("/apps/i18n/fr"));

        provider.reloadAllBundles();

        assertTrue(context.bundleContext()
                .getServiceReferences(ResourceBundle.class, "(locale=de)")
                .isEmpty());
        assertTrue(context.bundleContext()
                .getServiceReferences(ResourceBundle.class, "(locale=fr)")
                .isEmpty());
        assertTrue(context.bundleContext()
                .getServiceReferences(ResourceBundle.class, "(baseName=other)")
                .isEmpty());
        assertEquals("English", provider.getResourceBundle(Locale.ITALIAN).getString(KEY));
        context.create().resource("/apps/i18n/fr");
        provider.onChange(Collections.singletonList(new ResourceChange(ChangeType.CHANGED, "/apps/i18n/fr", false)));
        assertTrue("Deleted language roots must not remain in observation metadata", scheduledJobs.isEmpty());
    }

    /**
     * Fails a repository query after replacement construction has begun. The exception must propagate
     * without replacing the cached English bundle or changing any existing service registrations.
     */
    @Test
    public void failedFullReloadRetainsThePreviousGeneration() throws Exception {
        assumeTrue(preload);
        setupObservation();
        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        Set<ServiceReference<ResourceBundle>> previousServices = bundleServices();
        ResourceResolver resolver = factory.getServiceResourceResolver(null);
        AtomicInteger queries = new AtomicInteger();
        Mockito.doAnswer(query -> {
                    if (queries.incrementAndGet() == 2) {
                        throw new IllegalStateException("Repository query failed");
                    }
                    return dictionaries().iterator();
                })
                .when(resolver)
                .findResources(JcrResourceBundle.QUERY_LANGUAGE_ROOTS, "xpath");

        assertThrows(IllegalStateException.class, provider::reloadAllBundles);

        assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
        assertEquals("English", english.getString(KEY));
        assertEquals(previousServices, bundleServices());
    }

    /**
     * Denies the service resolver login for an eager full refresh. Existing cached bundles and their
     * service registrations must remain intact despite the refresh being unable to start construction.
     */
    @Test
    public void failedFullReloadLoginRetainsThePreviousGeneration() throws Exception {
        assumeTrue(preload);
        setupObservation();
        ResourceBundle english = provider.getResourceBundle(Locale.ENGLISH);
        Set<ServiceReference<ResourceBundle>> previousServices = bundleServices();
        Mockito.when(factory.getServiceResourceResolver(null)).thenThrow(new LoginException("Service login failed"));

        provider.reloadAllBundles();

        assertSame(english, provider.getResourceBundle(Locale.ENGLISH));
        assertEquals(previousServices, bundleServices());
    }

    /**
     * Publishes a targeted English update while an eager full refresh is paused during construction.
     * The superseded full refresh must retain the newer services and schedule a retry that exposes
     * the updated English translation through the German fallback chain.
     */
    @Test
    public void concurrentTargetedRefreshSupersedesStagedFullReload() throws Exception {
        assumeTrue(preload);
        setupObservation();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseNextDictionaryLoad(loading, resume);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> reload = executor.submit(provider::reloadAllBundles);
            assertTrue(loading.await(10, TimeUnit.SECONDS));
            context.resourceResolver()
                    .getResource("/apps/i18n/en/" + KEY)
                    .adaptTo(ModifiableValueMap.class)
                    .put("sling:message", "Concurrent update");
            provider.reloadBundle(new JcrResourceBundleProvider.Key(null, Locale.ENGLISH));
            ResourceBundle concurrent = provider.getResourceBundle(Locale.ENGLISH);
            Set<ServiceReference<ResourceBundle>> currentServices = bundleServices();
            resume.countDown();
            reload.get(10, TimeUnit.SECONDS);
            assertSame("Superseded reload must not publish", concurrent, provider.getResourceBundle(Locale.ENGLISH));
            assertEquals(currentServices, bundleServices());
            assertEquals(Collections.singleton(RELOAD_ALL), scheduledJobs.keySet());
            runScheduledWork();
            assertEquals(
                    "Concurrent update",
                    provider.getResourceBundle(Locale.GERMAN).getString(KEY));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /**
     * Pauses an uncached Italian lookup inside its service registration callback, then changes the
     * translation and completes a full refresh. The older lookup must retry against the new generation,
     * returning the current translation and parent without leaving a duplicate Italian service.
     */
    @Test
    public void oldInFlightLookupCannotOverwriteTheFullReload() throws Exception {
        assumeTrue(preload);
        setupObservation();
        Resource italian = createDictionary("/apps/i18n/it", "it");
        Resource message =
                createEntry(italian.getPath() + "/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "Old Italian");
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        ServiceListener listener = event -> {
            if (event.getType() == ServiceEvent.REGISTERED && first.getAndSet(false)) {
                registering.countDown();
                awaitReloadGate(resume);
            }
        };
        context.bundleContext().addServiceListener(listener, "(&(objectClass=java.util.ResourceBundle)(locale=it))");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResourceBundle> lookup = executor.submit(() -> provider.getResourceBundle(Locale.ITALIAN));
            assertTrue(registering.await(10, TimeUnit.SECONDS));
            message.adaptTo(ModifiableValueMap.class).put("sling:message", "New Italian");
            provider.reloadAllBundles();
            resume.countDown();
            assertEquals("New Italian", lookup.get(10, TimeUnit.SECONDS).getString(KEY));
            ResourceBundle current = provider.getResourceBundle(Locale.ITALIAN);
            assertEquals("New Italian", current.getString(KEY));
            assertSame(provider.getResourceBundle(Locale.ENGLISH), ((JcrResourceBundle) current).getParent());
            assertEquals(
                    1,
                    context.bundleContext()
                            .getServiceReferences(ResourceBundle.class, "(locale=it)")
                            .size());
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            context.bundleContext().removeServiceListener(listener);
        }
    }

    /**
     * Deactivates the provider while the first staged replacement is inside its registration callback.
     * Both the old services and the unpublished replacement must be unregistered exactly once,
     * leaving no ResourceBundle services after the refresh resumes.
     */
    @Test
    public void shutdownDuringFullReloadRegistrationLeavesNoServices() throws Exception {
        assumeTrue(preload);
        setupObservation();
        int originalServices = bundleServices().size();
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Map<Long, Integer> unregistrations = new ConcurrentHashMap<>();
        ServiceListener listener = event -> {
            if (event.getType() == ServiceEvent.REGISTERED) {
                registering.countDown();
                awaitReloadGate(resume);
            } else if (event.getType() == ServiceEvent.UNREGISTERING) {
                Long id = (Long) event.getServiceReference().getProperty(Constants.SERVICE_ID);
                unregistrations.merge(id, 1, Integer::sum);
            }
        };
        context.bundleContext().addServiceListener(listener, "(objectClass=java.util.ResourceBundle)");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> reload = executor.submit(provider::reloadAllBundles);
            assertTrue(registering.await(10, TimeUnit.SECONDS));
            provider.deactivate();
            resume.countDown();
            reload.get(10, TimeUnit.SECONDS);
            assertTrue(bundleServices().isEmpty());
            assertEquals(originalServices + 1, unregistrations.size());
            unregistrations.values().forEach(count -> assertEquals(Integer.valueOf(1), count));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            context.bundleContext().removeServiceListener(listener);
        }
    }

    /**
     * Queues a targeted German refresh, then changes the dictionary's locale to Italian and completes
     * a full refresh first. Executing the older queued job must not recreate the retired German service.
     */
    @Test
    public void fullReloadSupersedesAlreadyScheduledTargetedJobs() throws Exception {
        assumeTrue(preload);
        setupObservation();
        Resource german = context.resourceResolver().getResource("/apps/i18n/de");
        Resource entry = createEntry(german.getPath() + "/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "German");
        provider.onChange(Collections.singletonList(new ResourceChange(ChangeType.CHANGED, entry.getPath(), false)));
        assertFalse(scheduledJobs.isEmpty());
        german.adaptTo(ModifiableValueMap.class).put("jcr:language", "it");

        provider.reloadAllBundles();
        runScheduledWork();

        assertTrue(context.bundleContext()
                .getServiceReferences(ResourceBundle.class, "(locale=de)")
                .isEmpty());
        assertEquals("German", provider.getResourceBundle(Locale.ITALIAN).getString(KEY));
    }

    /**
     * Pauses an executing targeted German refresh during registration, then moves its dictionary to
     * Italian and completes a full refresh. Resuming the old refresh must neither publish its German
     * replacement nor recreate dependent German country bundles.
     */
    @Test
    public void runningTargetedRefreshCannotResurrectARetiredLocale() throws Exception {
        assumeTrue(preload);
        setupObservation();
        Resource german = context.resourceResolver().getResource("/apps/i18n/de");
        createEntry(german.getPath() + "/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "Moved message");
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        ServiceListener listener = event -> {
            if (event.getType() == ServiceEvent.REGISTERED && first.getAndSet(false)) {
                registering.countDown();
                awaitReloadGate(resume);
            }
        };
        context.bundleContext().addServiceListener(listener, "(&(objectClass=java.util.ResourceBundle)(locale=de))");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> targeted = executor.submit(
                    () -> provider.reloadBundle(new JcrResourceBundleProvider.Key(null, Locale.GERMAN)));
            assertTrue(registering.await(10, TimeUnit.SECONDS));
            german.adaptTo(ModifiableValueMap.class).put("jcr:language", "it");
            provider.reloadAllBundles();
            resume.countDown();
            targeted.get(10, TimeUnit.SECONDS);
            assertTrue(context.bundleContext()
                    .getServiceReferences(ResourceBundle.class, "(|(locale=de)(locale=de_DE))")
                    .isEmpty());
            assertEquals(
                    "Moved message", provider.getResourceBundle(Locale.ITALIAN).getString(KEY));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            context.bundleContext().removeServiceListener(listener);
        }
    }

    /**
     * Observes a German entry update while full-refresh construction is paused, without executing the
     * targeted job. Observation alone must invalidate staging, preserve the current services, and
     * schedule a fresh full refresh that makes the updated translation available.
     */
    @Test
    public void observationDuringFullReloadRequiresAFreshGeneration() throws Exception {
        assumeTrue(preload);
        setupObservation();
        Set<ServiceReference<ResourceBundle>> previousServices = bundleServices();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseNextDictionaryLoad(loading, resume);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> reload = executor.submit(provider::reloadAllBundles);
            assertTrue(loading.await(10, TimeUnit.SECONDS));
            Resource entry = createEntry("/apps/i18n/de/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "Observed update");
            provider.onChange(
                    Collections.singletonList(new ResourceChange(ChangeType.CHANGED, entry.getPath(), false)));
            resume.countDown();
            reload.get(10, TimeUnit.SECONDS);
            assertEquals("Aborted generation must not replace any service", previousServices, bundleServices());
            assertEquals(Collections.singleton(RELOAD_ALL), scheduledJobs.keySet());
            runScheduledWork();
            assertEquals(
                    "Observed update", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /**
     * Runs a full refresh with preloading disabled. Services must be removed immediately, with a new
     * bundle constructed only when a subsequent lookup requests it.
     */
    @Test
    public void fullReloadWithoutPreloadingRemainsLazy() throws Exception {
        assumeTrue(!preload);
        setupObservation();
        ResourceBundle old = provider.getResourceBundle(Locale.ENGLISH);
        provider.reloadAllBundles();
        assertTrue(bundleServices().isEmpty());
        assertNotSame(old, provider.getResourceBundle(Locale.ENGLISH));
    }

    private Set<ServiceReference<ResourceBundle>> bundleServices() throws Exception {
        return new HashSet<>(context.bundleContext().getServiceReferences(ResourceBundle.class, null));
    }

    private void pauseNextDictionaryLoad(CountDownLatch loading, CountDownLatch resume) throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        ResourceResolver resolver = factory.getServiceResourceResolver(null);
        Mockito.doAnswer(query -> {
                    if (first.getAndSet(false)) {
                        loading.countDown();
                        awaitReloadGate(resume);
                    }
                    return dictionaries().iterator();
                })
                .when(resolver)
                .findResources(JcrResourceBundle.QUERY_LANGUAGE_ROOTS, "xpath");
    }

    private static void awaitReloadGate(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for full reload", e);
        }
    }

    /**
     * Adds, updates, and removes a typed entry in a known German dictionary.
     * Only its bundles and dependent country bundles should refresh; removal restores the English fallback.
     */
    @Test
    public void knownMessageEntryChangesAreTargeted() throws Exception {
        setupObservation();
        Resource entry = createEntry("/apps/i18n/de/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "Added");
        assertTargetedChange(entry.getPath(), ChangeType.ADDED, "Added", (String) null);
        entry.adaptTo(ModifiableValueMap.class).put("sling:message", "Changed");
        assertTargetedChange(entry.getPath(), ChangeType.CHANGED, "Changed", (String) null);
        context.resourceResolver().delete(entry);
        assertTargetedChange(entry.getPath(), ChangeType.REMOVED, "English", (String) null);
    }

    @Test
    public void entryRemovalSchedulesRefreshWithoutResolverLogin() throws Exception {
        setupObservation();
        Resource entry = createEntry("/apps/i18n/de/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "German");
        assertTargetedChange(entry.getPath(), ChangeType.ADDED, "German", (String) null);
        context.resourceResolver().delete(entry);
        ResourceResolver resolver = factory.getServiceResourceResolver(null);
        Mockito.when(factory.getServiceResourceResolver(null)).thenThrow(new LoginException("Temporarily unavailable"));
        Mockito.clearInvocations(factory);

        provider.onChange(Collections.singletonList(new ResourceChange(ChangeType.REMOVED, entry.getPath(), false)));

        Mockito.verifyNoInteractions(factory);
        assertEquals(1, scheduledJobs.size());
        assertFalse(scheduledJobs.containsKey(RELOAD_ALL));
        Mockito.doReturn(resolver).when(factory).getServiceResourceResolver(null);
        runScheduledWork();
        assertEquals("English", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
        assertEquals("English", provider.getResourceBundle(Locale.GERMANY).getString(KEY));
    }

    @Test
    public void removalDoesNotUseResolverOpenedForEarlierEvent() throws Exception {
        setupObservation();
        Resource entry = createEntry("/apps/i18n/de/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "German");
        assertTargetedChange(entry.getPath(), ChangeType.ADDED, "German", (String) null);
        ResourceResolver resolver = factory.getServiceResourceResolver(null);
        String removedPath = "/apps/i18n/de/removed";
        entry.adaptTo(ModifiableValueMap.class).put("sling:message", "Changed");
        Mockito.clearInvocations(resolver);

        provider.onChange(Arrays.asList(
                new ResourceChange(ChangeType.CHANGED, entry.getPath(), false),
                new ResourceChange(ChangeType.REMOVED, removedPath, false)));

        Mockito.verify(resolver).getResource(entry.getPath());
        Mockito.verify(resolver, Mockito.never()).getResource(removedPath);
        assertEquals(1, scheduledJobs.size());
        assertFalse(scheduledJobs.containsKey(RELOAD_ALL));
        runScheduledWork();
        assertEquals("Changed", provider.getResourceBundle(Locale.GERMANY).getString(KEY));
    }

    /**
     * Verifies that entries using the message mixin receive the same targeted refresh
     * treatment as entries using the message primary type.
     */
    @Test
    public void knownMessageMixinChangesAreTargeted() throws Exception {
        setupObservation();
        Resource entry = createEntry("/apps/i18n/de/" + KEY, "nt:unstructured", "Added");
        entry.adaptTo(ModifiableValueMap.class).put("jcr:mixinTypes", new String[] {JcrResourceBundle.MIXIN_MESSAGE});
        assertTargetedChange(entry.getPath(), ChangeType.ADDED, "Added", (String) null);
        entry.adaptTo(ModifiableValueMap.class).put("sling:message", "Changed");
        assertTargetedChange(entry.getPath(), ChangeType.CHANGED, "Changed", (String) null);
        context.resourceResolver().delete(entry);
        assertTargetedChange(entry.getPath(), ChangeType.REMOVED, "English", (String) null);
    }

    /**
     * Preserves observation support for entries loaded by their message properties alone,
     * without requiring a message primary type or mixin.
     */
    @Test
    public void knownUntypedEntryChangesRemainTargeted() throws Exception {
        setupObservation();
        Resource entry = createEntry("/apps/i18n/de/" + KEY, "nt:unstructured", "Added");
        assertTargetedChange(entry.getPath(), ChangeType.ADDED, "Added", (String) null);
        entry.adaptTo(ModifiableValueMap.class).put("sling:message", "Changed");
        assertTargetedChange(entry.getPath(), ChangeType.CHANGED, "Changed", (String) null);
        context.resourceResolver().delete(entry);
        assertTargetedChange(entry.getPath(), ChangeType.REMOVED, "English", (String) null);
    }

    /**
     * A language mixin without a locale does not define a new dictionary root.
     * Such an entry must still use the surrounding dictionary's targeted refresh.
     */
    @Test
    public void languageMixinWithoutLocaleDoesNotIntroduceDictionary() throws Exception {
        setupObservation();
        Resource entry = createEntry("/apps/i18n/de/" + KEY, "nt:unstructured", "Added");
        entry.adaptTo(ModifiableValueMap.class).put("jcr:mixinTypes", new String[] {JcrResourceBundle.MIXIN_LANGUAGE});
        assertTargetedChange(entry.getPath(), ChangeType.ADDED, "Added", (String) null);
    }

    /**
     * A shared root contributes to unnamed bundles and multiple named bundles.
     * Refresh every matching basename, but preserve bundles using only the other root.
     */
    @Test
    public void sharedRootRefreshesEveryAffectedBasename() throws Exception {
        setupObservation();
        context.resourceResolver()
                .getResource("/apps/i18n/de")
                .adaptTo(ModifiableValueMap.class)
                .put("sling:basename", new String[] {"checkout", "store"});
        Resource other = createDictionary("/apps/i18n/de-other", "de", "store", "other");
        context.create().resource(other.getPath() + "/secondary", "sling:message", "Other dictionary");
        provider.clearCache();
        cacheBundles();
        Resource entry = createEntry("/apps/i18n/de/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "Added");
        assertTargetedChange(entry.getPath(), ChangeType.ADDED, "Added", null, "checkout", "store");
        entry.adaptTo(ModifiableValueMap.class).put("sling:message", "Changed");
        assertTargetedChange(entry.getPath(), ChangeType.CHANGED, "Changed", null, "checkout", "store");
        context.resourceResolver().delete(entry);
        assertTargetedChange(entry.getPath(), ChangeType.REMOVED, "English", null, "checkout", "store");
    }

    /**
     * A typed entry outside known roots must trigger discovery and expose its translation.
     * The sibling path de-extra must not be mistaken for a descendant of the known de root.
     */
    @Test
    public void unknownMessageEntryTriggersDiscovery() throws Exception {
        setupObservation();
        createDictionary("/apps/i18n/de-extra", "de");
        Resource entry = createEntry("/apps/i18n/de-extra/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "Discovered");
        assertFullReload(new ResourceChange(ChangeType.ADDED, entry.getPath(), false));
        assertEquals("Discovered", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

    /**
     * A changed entry carrying the message mixin can reveal an unknown dictionary,
     * even when the event is not an addition.
     */
    @Test
    public void unknownMessageMixinTriggersDiscovery() throws Exception {
        setupObservation();
        createDictionary("/apps/i18n/de-extra", "de");
        Resource entry = createEntry("/apps/i18n/de-extra/" + KEY, "nt:unstructured", "Discovered");
        entry.adaptTo(ModifiableValueMap.class).put("jcr:mixinTypes", new String[] {JcrResourceBundle.MIXIN_MESSAGE});
        assertFullReload(new ResourceChange(ChangeType.CHANGED, entry.getPath(), false));
        assertEquals("Discovered", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

    /**
     * Adding a language root must trigger discovery even when its entries have no message type.
     */
    @Test
    public void unknownLanguageRootTriggersDiscovery() throws Exception {
        setupObservation();
        Resource root = createDictionary("/apps/i18n/de-extra", "de");
        createEntry(root.getPath() + "/" + KEY, "nt:unstructured", "Discovered");
        assertFullReload(new ResourceChange(ChangeType.ADDED, root.getPath(), false));
        assertEquals("Discovered", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

    /**
     * A new root nested below a known dictionary can introduce a different locale and basename.
     * Rediscovery must replace the previously cached empty Italian bundle.
     */
    @Test
    public void nestedLanguageRootTriggersDiscovery() throws Exception {
        setupObservation();
        ResourceBundle italian = provider.getResourceBundle("checkout", Locale.ITALIAN);
        Resource root = createDictionary("/apps/i18n/de/nested", "it", "checkout");
        createEntry(root.getPath() + "/" + KEY, "nt:unstructured", "Italian");
        assertFullReload(new ResourceChange(ChangeType.ADDED, root.getPath(), false));
        assertNotSame(italian, provider.getResourceBundle("checkout", Locale.ITALIAN));
        assertEquals(
                "Italian",
                provider.getResourceBundle("checkout", Locale.ITALIAN).getString(KEY));
    }

    /**
     * An unknown dictionary in an event batch must promote the batch to a full reload,
     * even if an earlier event only affected a known dictionary.
     */
    @Test
    public void discoveryInBatchIsNotHiddenByKnownEntry() throws Exception {
        setupObservation();
        Resource known = createEntry("/apps/i18n/de/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "German");
        createDictionary("/apps/i18n/fr-extra", "fr");
        Resource unknown = createEntry("/apps/i18n/fr-extra/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "French");
        assertFullReload(
                new ResourceChange(ChangeType.CHANGED, known.getPath(), false),
                new ResourceChange(ChangeType.ADDED, unknown.getPath(), false));
        assertEquals("German", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
        assertEquals("French", provider.getResourceBundle(Locale.FRENCH).getString(KEY));
    }

    /**
     * Changing a root's locale must rediscover ownership: its translation moves to Italian,
     * while German returns to its English fallback.
     */
    @Test
    public void rootLocaleChangeTriggersDiscovery() throws Exception {
        setupObservation();
        cacheGermanEntry();
        Resource root = context.resourceResolver().getResource("/apps/i18n/de");
        root.adaptTo(ModifiableValueMap.class).put("jcr:language", "it");
        assertFullReload(new ResourceChange(ChangeType.CHANGED, root.getPath(), false));
        assertEquals("English", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
        assertEquals("German", provider.getResourceBundle(Locale.ITALIAN).getString(KEY));
    }

    /**
     * Changing root basename metadata must invalidate cached named bundles so that
     * newly matching basenames receive the dictionary and nonmatching ones retain their fallback.
     */
    @Test
    public void rootBasenameChangeTriggersDiscovery() throws Exception {
        setupObservation();
        cacheGermanEntry();
        Resource root = context.resourceResolver().getResource("/apps/i18n/de");
        root.adaptTo(ModifiableValueMap.class).put("sling:basename", new String[] {"checkout"});
        assertFullReload(new ResourceChange(ChangeType.CHANGED, root.getPath(), false));
        assertEquals(
                "German", provider.getResourceBundle("checkout", Locale.GERMAN).getString(KEY));
        assertEquals(
                "English", provider.getResourceBundle("store", Locale.GERMAN).getString(KEY));
    }

    /**
     * Removing the language mixin makes a known root ineligible for discovery.
     * A full refresh must discard its previously cached translations.
     */
    @Test
    public void rootLanguageMixinRemovalTriggersDiscovery() throws Exception {
        setupObservation();
        cacheGermanEntry();
        Resource root = context.resourceResolver().getResource("/apps/i18n/de");
        root.adaptTo(ModifiableValueMap.class).remove("jcr:mixinTypes");
        assertFullReload(new ResourceChange(ChangeType.CHANGED, root.getPath(), false));
        assertEquals("English", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

    /**
     * Removing a known root requires a full refresh even though the resource can no longer
     * be inspected; its former translations must fall back to English.
     */
    @Test
    public void rootRemovalTriggersDiscovery() throws Exception {
        setupObservation();
        cacheGermanEntry();
        context.resourceResolver().delete(context.resourceResolver().getResource("/apps/i18n/de"));
        assertFullReload(new ResourceChange(ChangeType.REMOVED, "/apps/i18n/de", false));
        assertEquals("English", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

    private void cacheGermanEntry() {
        createEntry("/apps/i18n/de/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "German");
        provider.clearCache();
        cacheBundles();
        assertEquals("German", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

    /**
     * Removing the locale property invalidates a known dictionary just like removing its
     * language mixin, so cached translations must not survive rediscovery.
     */
    @Test
    public void rootLanguagePropertyRemovalTriggersDiscovery() throws Exception {
        setupObservation();
        cacheGermanEntry();
        Resource root = context.resourceResolver().getResource("/apps/i18n/de");
        root.adaptTo(ModifiableValueMap.class).remove("jcr:language");
        assertFullReload(new ResourceChange(ChangeType.CHANGED, root.getPath(), false));
        assertEquals("English", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

    /**
     * A removal event on a dictionary container must invalidate all roots below it,
     * without relying on separate removal events for each descendant.
     */
    @Test
    public void dictionaryAncestorRemovalTriggersDiscovery() throws Exception {
        setupObservation();
        context.resourceResolver().delete(context.resourceResolver().getResource("/apps/i18n"));
        assertFullReload(new ResourceChange(ChangeType.REMOVED, "/apps/i18n", false));
        assertEquals(KEY, provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

    /**
     * Shared string prefixes do not imply dictionary ancestry.
     * Changes and removals under decorations must not invalidate the dictionary at de.
     */
    @Test
    public void rootPrefixDoesNotMatchSiblingPaths() throws Exception {
        setupObservation();
        Resource entry = createEntry("/apps/i18n/decorations/" + KEY, "nt:unstructured", "Not a dictionary");
        assertIgnoredChange(new ResourceChange(ChangeType.CHANGED, entry.getPath(), false));
        context.resourceResolver().delete(entry.getParent());
        assertIgnoredChange(new ResourceChange(ChangeType.REMOVED, "/apps/i18n/decorations", false));
    }

    /**
     * Path filters apply before both targeted refresh and discovery, including excluded
     * descendants of known roots, excluded roots, and paths outside the include set.
     */
    @Test
    public void excludedAndNonIncludedChangesAreIgnored() throws Exception {
        setupObservation();
        Resource known = createEntry("/apps/i18n/de/excluded/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "Excluded");
        Resource excludedRoot = createDictionary("/apps/excluded/de", "de");
        Resource excluded =
                createEntry(excludedRoot.getPath() + "/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "Excluded");
        Resource outsideRoot = createDictionary("/content/i18n/de", "de");
        Resource outside =
                createEntry(outsideRoot.getPath() + "/" + KEY, JcrResourceBundle.RT_MESSAGE_ENTRY, "Outside");
        assertIgnoredChange(
                new ResourceChange(ChangeType.ADDED, known.getPath(), false),
                new ResourceChange(ChangeType.CHANGED, excluded.getPath(), false),
                new ResourceChange(ChangeType.CHANGED, excludedRoot.getPath(), false),
                new ResourceChange(ChangeType.REMOVED, known.getPath(), false),
                new ResourceChange(ChangeType.ADDED, outside.getPath(), false));
    }

    private Resource createJsonDictionary(String path, String message) {
        Resource root = createDictionary(path, "de");
        root.adaptTo(ModifiableValueMap.class).put("jcr:primaryType", "nt:file");
        context.create()
                .resource(
                        path + "/jcr:content",
                        "jcr:primaryType",
                        "nt:resource",
                        "jcr:mimeType",
                        "application/json",
                        "jcr:data",
                        jsonData(message));
        return root;
    }

    private byte[] jsonData(String message) {
        return ("{\"" + KEY + "\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Direct events on a known JSON dictionary's content child allow targeted refresh.
     * Replacing, removing, and restoring the data must update translations without replacing unrelated bundles.
     */
    @Test
    public void knownJsonContentChangesAreTargeted() throws Exception {
        setupObservation();
        Resource root = createJsonDictionary("/apps/i18n/de.json", "Original");
        provider.clearCache();
        cacheBundles();
        Resource content = root.getChild("jcr:content");
        content.adaptTo(ModifiableValueMap.class).put("jcr:data", jsonData("Changed"));
        assertTargetedChange(content.getPath(), ChangeType.CHANGED, "Changed", (String) null);
        context.resourceResolver().delete(content);
        assertTargetedChange(root.getPath() + "/jcr:content", ChangeType.REMOVED, "English", (String) null);
        context.create()
                .resource(
                        root.getPath() + "/jcr:content",
                        "jcr:primaryType",
                        "nt:resource",
                        "jcr:mimeType",
                        "application/json",
                        "jcr:data",
                        jsonData("Added"));
        assertTargetedChange(root.getPath() + "/jcr:content", ChangeType.ADDED, "Added", (String) null);
    }

    /**
     * Adding an unknown JSON root must rediscover dictionaries and expose its translations.
     */
    @Test
    public void unknownJsonDictionaryTriggersDiscovery() throws Exception {
        setupObservation();
        Resource root = createJsonDictionary("/apps/i18n/de.json", "Discovered");
        assertFullReload(new ResourceChange(ChangeType.ADDED, root.getPath(), false));
        assertEquals("Discovered", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

    /**
     * Events reported at the JSON root retain full-refresh behavior, unlike content-child events.
     * Both a changed root and its subsequent removal must update the returned translations.
     */
    @Test
    public void knownJsonRootChangesTriggerDiscovery() throws Exception {
        setupObservation();
        Resource root = createJsonDictionary("/apps/i18n/de.json", "Original");
        provider.clearCache();
        cacheBundles();
        root.getChild("jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:data", jsonData("Changed"));
        assertFullReload(new ResourceChange(ChangeType.CHANGED, root.getPath(), false));
        assertEquals("Changed", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
        context.resourceResolver().delete(root);
        assertFullReload(new ResourceChange(ChangeType.REMOVED, root.getPath(), false));
        assertEquals("English", provider.getResourceBundle(Locale.GERMAN).getString(KEY));
    }

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
