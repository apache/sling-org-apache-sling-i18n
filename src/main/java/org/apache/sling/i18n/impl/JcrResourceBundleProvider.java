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
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.observation.ExternalResourceChangeListener;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.i18n.ResourceBundleProvider;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.util.tracker.BundleTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.sling.i18n.impl.JcrResourceBundle.PROP_BASENAME;
import static org.apache.sling.i18n.impl.JcrResourceBundle.PROP_LANGUAGE;
import static org.apache.sling.i18n.impl.JcrResourceBundle.PROP_PATH;

/**
 * The <code>JcrResourceBundleProvider</code> implements the
 * <code>ResourceBundleProvider</code> interface creating
 * <code>ResourceBundle</code> instances from resources stored in the
 * repository.
 */
@Component(
        service = {ResourceBundleProvider.class, ResourceChangeListener.class},
        property = {
            Constants.SERVICE_DESCRIPTION + "=Apache Sling I18n Resource Bundle Provider",
            Constants.SERVICE_VENDOR + "=The Apache Software Foundation",
            ResourceChangeListener.PATHS + "=/",
            ResourceChangeListener.CHANGES + "=ADDED",
            ResourceChangeListener.CHANGES + "=REMOVED",
            ResourceChangeListener.CHANGES + "=CHANGED"
        })
@Designate(ocd = Config.class)
public class JcrResourceBundleProvider
        implements ResourceBundleProvider, ResourceChangeListener, ExternalResourceChangeListener {

    /** default log */
    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * A regular expression pattern matching all custom country codes.
     * @see <a href="https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2#User-assigned_code_elements">User-assigned code elements</a>
     */
    private static final Pattern USER_ASSIGNED_COUNTRY_CODES_PATTERN = Pattern.compile("aa|q[m-z]|x[a-z]|zz");

    @Reference
    private Scheduler scheduler;

    /** job names of scheduled jobs for reloading individual bundles */
    private final Collection<String> scheduledJobNames = Collections.synchronizedList(new ArrayList<String>());

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private ServiceUserMapped serviceUserMapped;

    /**
     * The default Locale as configured with the <i>locale.default</i>
     * configuration property. This defaults to <code>Locale.ENGLISH</code> if
     * the configuration property is not set.
     */
    private volatile Locale defaultLocale = Locale.ENGLISH;

    /**
     * Registry of the loaded <code>resource bundles</code> and the associated <code>service registrations</code>
     */
    private ResourceBundleRegistry resourceBundleRegistry;

    private final ConcurrentHashMap<Key, Semaphore> loadingGuards = new ConcurrentHashMap<>();

    /**
     * paths from which JCR resource bundles have been loaded
     */
    private final Set<String> languageRootPaths = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * Return root resource bundle as created on-demand by
     * {@link #getRootResourceBundle()}.
     */
    private volatile ResourceBundle rootResourceBundle;

    private BundleTracker<Set<LocatorPaths>> locatorPathsTracker;
    private List<LocatorPaths> locatorPaths = new CopyOnWriteArrayList<>();

    /**
     * Filter to check for allowed paths
     */
    private volatile PathFilter pathFilter;

    private volatile boolean preloadBundles;

    private volatile long invalidationDelay;

    /**
     * Add a set of paths to the set that are inspected to
     * look for resource bundle resources
     *
     * @param locatorPathsSet set of locator paths to check
     */
    public void registerLocatorPaths(Set<LocatorPaths> locatorPathsSet) {
        this.locatorPaths.addAll(locatorPathsSet);
        clearCache();
    }

    /**
     * Remove a set of paths from the set that are inspected to
     * look for resource bundle resources
     *
     * @param locatorPathsSet set of locator paths to no longer check
     */
    public void unregisterLocatorPaths(Set<LocatorPaths> locatorPathsSet) {
        this.locatorPaths.removeAll(locatorPathsSet);
        clearCache();
    }

    private ResourceResolver createResourceResolver() throws LoginException {
        return resourceResolverFactory.getServiceResourceResolver(null);
    }

    // ---------- ResourceBundleProvider ---------------------------------------

    /**
     * Returns the configured default <code>Locale</code> which is used as a
     * fallback for {@link #getResourceBundle(Locale)} and also as the basis for
     * any messages requested from resource bundles.
     */
    @Override
    public Locale getDefaultLocale() {
        return defaultLocale;
    }

    /**
     * Returns the <code>ResourceBundle</code> for the given
     * <code>locale</code>.
     *
     * @param locale The <code>Locale</code> for which to return the resource
     *            bundle. If this is <code>null</code> the configured
     *            {@link #getDefaultLocale() default locale} is assumed.
     * @return The <code>ResourceBundle</code> for the given locale.
     * @throws MissingResourceException If the <code>ResourceResolver</code>
     *             is not available to access the resources.
     */
    @Override
    public ResourceBundle getResourceBundle(final Locale locale) {
        return getResourceBundle(null, locale);
    }

    @Override
    public ResourceBundle getResourceBundle(final String baseName, Locale locale) {
        return getResourceBundleInternal(null, baseName, locale);
    }

    // ---------- ResourceChangeListener ------------------------------------------------

    private static final class ChangeStatus {
        public ResourceResolver resourceResolver;
        public boolean reloadAll = false;
        public final Set<JcrResourceBundle> reloadBundles = new HashSet<>();
    }

    @Override
    public void onChange(final List<ResourceChange> changes) {
        if (resourceBundleRegistry.isClosed()) {
            return;
        }
        final ChangeStatus status = new ChangeStatus();
        try {
            for (final ResourceChange change : changes) {

                if (!this.pathFilter.includePath(change.getPath())) {
                    continue;
                }
                this.onChange(status, change);
                // if we need to reload all, we can skip all other events
                if (status.reloadAll) {
                    break;
                }
            }
            if (status.reloadAll) {
                this.scheduleReloadBundles(true);
            } else {
                for (final JcrResourceBundle bundle : status.reloadBundles) {
                    this.scheduleReloadBundle(bundle);
                }
            }
        } catch (final LoginException le) {
            log.error("Unable to get service resource resolver.", le);
        } finally {
            if (status.resourceResolver != null) {
                status.resourceResolver.close();
            }
        }
    }

    private void onChange(final ChangeStatus status, final ResourceChange change) throws LoginException {
        log.debug("onChange: Detecting change {} for path '{}'", change.getType(), change.getPath());

        // if this change was on languageRootPath level this might change basename and locale as well, therefore
        // invalidate everything
        if (languageRootPaths.contains(change.getPath())) {
            log.debug(
                    "onChange: Detected change of cached language root '{}', removing all cached ResourceBundles",
                    change.getPath());
            status.reloadAll = true;
        } else {
            for (final String root : languageRootPaths) {
                if (change.getPath().startsWith(root)) {
                    // figure out which JcrResourceBundles from the cached ones is affected
                    for (JcrResourceBundle bundle : resourceBundleRegistry.getResourceBundles()) {
                        if (bundle.getLanguageRootPaths().contains(root)) {
                            // reload it
                            log.debug(
                                    "onChange: Resource changes below '{}', reloading ResourceBundle '{}'",
                                    root,
                                    bundle);
                            status.reloadBundles.add(bundle);
                        }
                    }
                }
            }

            // may be a completely new dictionary
            if (status.resourceResolver == null) {
                status.resourceResolver = createResourceResolver();
            }
            if (isDictionaryResource(status.resourceResolver, change)) {
                status.reloadAll = true;
            }
        }
    }

    private boolean isDictionaryResource(final ResourceResolver resolver, final ResourceChange change) {
        // language node changes happen quite frequently (https://issues.apache.org/jira/browse/SLING-2881)
        // therefore only consider changes either for sling:MessageEntry's
        // or for JSON dictionaries
        // get valuemap
        final Resource resource = resolver.getResource(change.getPath());
        if (resource == null) {
            log.trace("Could not get resource for '{}' for event {}", change.getPath(), change.getType());
            return false;
        }
        if (resource.getResourceType() == null) {
            return false;
        }
        if (resource.isResourceType(JcrResourceBundle.RT_MESSAGE_ENTRY)) {
            log.debug(
                    "Found new dictionary entry: New {} resource in '{}' detected",
                    JcrResourceBundle.RT_MESSAGE_ENTRY,
                    change.getPath());
            return true;
        }
        final ValueMap valueMap = resource.getValueMap();
        // FIXME: derivatives from mix:Message are not detected
        if (hasMixin(valueMap, JcrResourceBundle.MIXIN_MESSAGE)) {
            log.debug(
                    "Found new dictionary entry: New {} resource in '{}' detected",
                    JcrResourceBundle.MIXIN_MESSAGE,
                    change.getPath());
            return true;
        }
        if (change.getPath().endsWith(".json")) {
            // check for mixin
            if (hasMixin(valueMap, JcrResourceBundle.MIXIN_LANGUAGE)) {
                log.debug(
                        "Found new dictionary: New {} resource in '{}' detected",
                        JcrResourceBundle.MIXIN_LANGUAGE,
                        change.getPath());
                return true;
            }
        }
        return false;
    }

    private boolean hasMixin(ValueMap valueMap, String mixin) {
        final String[] mixins = valueMap.get(JcrResourceBundle.PROP_MIXINS, String[].class);
        if (mixins != null) {
            for (final String m : mixins) {
                if (mixin.equals(m)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void scheduleReloadBundles(final boolean withDelay) {
        // cancel all reload individual bundle jobs!
        synchronized (scheduledJobNames) {
            for (String scheduledJobName : scheduledJobNames) {
                scheduler.unschedule(scheduledJobName);
            }
        }
        scheduledJobNames.clear();
        // defer this job
        final ScheduleOptions options;
        if (withDelay) {
            options = scheduler.AT(new Date(System.currentTimeMillis() + this.invalidationDelay));
        } else {
            options = scheduler.NOW();
        }
        options.name("ResourceBundleProvider: reload all resource bundles");
        scheduler.schedule(
                new Runnable() {
                    @Override
                    public void run() {
                        log.info("Reloading all resource bundles");
                        clearCache();
                        preloadBundles();
                    }
                },
                options);
    }

    private void scheduleReloadBundle(final JcrResourceBundle bundle) {
        final Key key = new Key(bundle.getBaseName(), bundle.getLocale());

        // defer this job
        ScheduleOptions options = scheduler.AT(new Date(System.currentTimeMillis() + this.invalidationDelay));
        final String jobName = "ResourceBundleProvider: reload bundle with key " + key.toString();
        scheduledJobNames.add(jobName);
        options.name(jobName);
        scheduler.schedule(
                new Runnable() {
                    @Override
                    public void run() {
                        reloadBundle(key);
                        scheduledJobNames.remove(jobName);
                    }
                },
                options);
    }

    void reloadBundle(final Key key) {
        log.info("Reloading resource bundle for {}", key);
        if (!this.preloadBundles) {
            resourceBundleRegistry.unregisterResourceBundle(key);
        }

        Collection<JcrResourceBundle> dependentBundles = new ArrayList<>();
        // this bundle might be a parent of a cached bundle -> invalidate those dependent bundles as well
        for (JcrResourceBundle bundle : resourceBundleRegistry.getResourceBundles()) {
            if (bundle.getParent() instanceof JcrResourceBundle) {
                JcrResourceBundle parentBundle = (JcrResourceBundle) bundle.getParent();
                Key parentKey = new Key(parentBundle.getBaseName(), parentBundle.getLocale());
                if (parentKey.equals(key)) {
                    log.debug(
                            "Also invalidate dependent bundle {} which has bundle {} as parent", bundle, parentBundle);
                    dependentBundles.add(bundle);
                }
            }
        }
        for (JcrResourceBundle dependentBundle : dependentBundles) {
            reloadBundle(new Key(dependentBundle.getBaseName(), dependentBundle.getLocale()));
        }

        if (this.preloadBundles && !resourceBundleRegistry.isClosed()) {
            // reload the bundle from the repository (will also fill cache and register as a service)
            getResourceBundleInternal(null, key.baseName, key.locale, true);
        }
    }

    // ---------- SCR Integration ----------------------------------------------

    /**
     * Activates and configures this component with the repository access
     * details and the default locale to use
     * @throws LoginException
     */
    @Activate
    protected void activate(final BundleContext context, final Config config) throws LoginException {
        this.defaultLocale = toLocale(config.locale_default());
        this.preloadBundles = config.preload_bundles();
        this.invalidationDelay = config.invalidation_delay();
        this.pathFilter = new PathFilter(config.included_paths(), config.excluded_paths());

        this.resourceBundleRegistry = new ResourceBundleRegistry(context);

        this.locatorPathsTracker = new BundleTracker<>(context, Bundle.ACTIVE, new LocatorPathsTracker(this));
        this.locatorPathsTracker.open();

        if (this.resourceResolverFactory != null) { // this is only null during test execution!
            scheduleReloadBundles(false);
        }
    }

    @Deactivate
    protected void deactivate() {

        if (this.locatorPathsTracker != null) {
            this.locatorPathsTracker.close();
            this.locatorPathsTracker = null;
        }

        if (this.resourceBundleRegistry != null) {
            this.resourceBundleRegistry.close();
        }

        clearCache();
    }

    // ---------- internal -----------------------------------------------------

    /**
     * Internal implementation of the {@link #getResourceBundle(Locale)} method
     * employing the cache of resource bundles. Creates the bundle if not
     * already cached.
     *
     * @throws MissingResourceException If the resource bundles needs to be
     *             created and the <code>ResourceResolver</code> is not
     *             available to access the resources.
     */
    private ResourceBundle getResourceBundleInternal(
            ResourceResolver optionalResolver, String baseName, Locale locale) {
        return getResourceBundleInternal(optionalResolver, baseName, locale, false);
    }

    private ResourceBundle getResourceBundleInternal(
            ResourceResolver optionalResolver, final String baseName, Locale locale, final boolean forceReload) {
        if (locale == null) {
            locale = defaultLocale;
        }

        final Key key = new Key(baseName, locale);
        JcrResourceBundle resourceBundle = !forceReload ? resourceBundleRegistry.getResourceBundle(key) : null;
        if (resourceBundle != null) {
            log.debug("getResourceBundleInternal({}): got cache hit on first try", key);
        } else {
            if (loadingGuards.get(key) == null) {
                loadingGuards.putIfAbsent(key, new Semaphore(1));
            }
            final Semaphore loadingGuard = loadingGuards.get(key);
            try {
                loadingGuard.acquire();
                resourceBundle = !forceReload ? resourceBundleRegistry.getResourceBundle(key) : null;
                if (resourceBundle != null) {
                    log.debug("getResourceBundleInternal({}): got cache hit on second try", key);
                } else {
                    log.debug("getResourceBundleInternal({}): reading from Repository", key);
                    ResourceResolver localResolver = null;
                    try {
                        if (optionalResolver == null) {
                            localResolver = createResourceResolver();
                            optionalResolver = localResolver;
                        }

                        resourceBundle = createResourceBundle(optionalResolver, key.baseName, key.locale);
                        resourceBundleRegistry.registerResourceBundle(key, resourceBundle);

                        final Set<String> languageRoots = resourceBundle.getLanguageRootPaths();
                        this.languageRootPaths.addAll(languageRoots);

                        log.debug("Key {} - added service registration and language roots {}", key, languageRoots);
                        log.info("Currently loaded dictionaries across all locales: {}", languageRootPaths);

                    } catch (final LoginException le) {
                        throw (MissingResourceException) new MissingResourceException(
                                        "Unable to create service resource resolver", baseName, locale.toString())
                                .initCause(le);
                    } finally {
                        if (localResolver != null) {
                            localResolver.close();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.interrupted();
            } finally {
                loadingGuard.release();
            }
        }
        log.trace("getResourceBundleInternal({}) ==> {}", key, resourceBundle);
        return resourceBundle;
    }

    /**
     * Creates the resource bundle for the give locale.
     *
     * @throws MissingResourceException If the <code>ResourceResolver</code>
     *             is not available to access the resources.
     */
    private JcrResourceBundle createResourceBundle(
            final ResourceResolver resolver, final String baseName, final Locale locale) {
        final JcrResourceBundle bundle =
                new JcrResourceBundle(locale, baseName, resolver, locatorPaths, this.pathFilter);

        // set parent resource bundle
        Locale parentLocale = getParentLocale(locale);
        if (parentLocale != null) {
            bundle.setParent(getResourceBundleInternal(resolver, baseName, parentLocale));
        } else {
            bundle.setParent(getRootResourceBundle());
        }

        return bundle;
    }

    /**
     * Returns the parent locale of the given locale. The parent locale is the
     * locale of a locale is defined as follows:
     * <ol>
     * <li>If locale has script and variant, the parent locale is the locale with
     * the same language , script and country without the variant </li>
     * <li>If the locale has a script but no variant, the parent locale is the
     * locale with the same language and script without the country.</li>
     * <li>If the locale has a script but no country , the parent locale is the
     * locale with the same language without the script.</li>
     * <li>If the locale has an variant, the parent locale is the locale with
     * the same language and country without the variant.</li>
     * <li>If the locale has no variant but a country, the parent locale is the
     * locale with the same language but neither country nor variant.</li>
     * <li>If the locale has no country and not variant and whose language is
     * different from the language of the the configured default locale, the
     * parent locale is the configured default locale.</li>
     * <li>Otherwise there is no parent locale and <code>null</code> is
     * returned.</li>
     * </ol>
     */
    private Locale getParentLocale(Locale locale) {
        if (locale.getScript().length() != 0 && locale.getVariant().length() != 0) {
            return new Locale.Builder().setLanguage(locale.getLanguage()).setRegion(locale.getCountry()).setScript(locale.getScript()).build();
        } else if (locale.getScript().length() != 0 && locale.getCountry().length() != 0) {
            return new Locale.Builder().setLanguage(locale.getLanguage()).setScript(locale.getScript()).build();
        } else if (locale.getScript().length() !=0) {
            return new Locale(locale.getLanguage());
        } else if (locale.getVariant().length() != 0) {
            return new Locale(locale.getLanguage(), locale.getCountry());
        } else if (locale.getCountry().length() != 0) {
            return new Locale(locale.getLanguage());
        } else if (!locale.getLanguage().equals(defaultLocale.getLanguage())) {
            return defaultLocale;
        }
        // no more parents
        return null;
    }

    /**
     * Returns a ResourceBundle which is used as the root resource bundle, that
     * is the ultimate parent:
     * <ul>
     * <li><code>getLocale()</code> returns Locale("", "", "")</li>
     * <li><code>handleGetObject(String key)</code> returns the <code>key</code></li>
     * <li><code>getKeys()</code> returns an empty enumeration.
     * </ul>
     *
     * @return The root resource bundle
     */
    private ResourceBundle getRootResourceBundle() {
        if (rootResourceBundle == null) {
            rootResourceBundle = new RootResourceBundle();
        }
        return rootResourceBundle;
    }

    void clearCache() {
        languageRootPaths.clear();
        resourceBundleRegistry.unregisterAll();
    }

    private void preloadBundles() {
        if (this.preloadBundles && !resourceBundleRegistry.isClosed()) {
            try (final ResourceResolver resolver = createResourceResolver()) {
                final Iterator<Map<String, Object>> bundles =
                        resolver.queryResources(JcrResourceBundle.QUERY_LANGUAGE_ROOTS, "xpath");
                final Set<Key> usedKeys = new HashSet<>();
                while (bundles.hasNext()) {
                    final Map<String, Object> bundle = bundles.next();
                    if (bundle.containsKey(PROP_LANGUAGE) && bundle.containsKey(PROP_PATH)) {
                        final String path = bundle.get(PROP_PATH).toString();
                        final String language = bundle.get(PROP_LANGUAGE).toString();
                        if (this.pathFilter.includePath(path)) {
                            final Locale locale = toLocale(language);
                            final String baseName = bundle.containsKey(PROP_BASENAME)
                                    ? bundle.get(PROP_BASENAME).toString()
                                    : null;
                            final Key key = new Key(baseName, locale);
                            if (usedKeys.add(key)) {
                                getResourceBundleInternal(resolver, baseName, locale);
                            }
                        } else {
                            log.warn(
                                    "Ignoring i18n bundle for language {} at {} because it is not included by the path filter",
                                    language,
                                    path);
                        }
                    }
                }
            } catch (final LoginException le) {
                log.error("Unable to create service user resource resolver.", le);
            }
        }
    }

    /**
     * Converts the given <code>localeString</code> to a valid
     * <code>java.util.Locale</code>. It must either be in the format specified by
     * {@link Locale#toString()} or in <a href="https://tools.ietf.org/html/bcp47">BCP 47 format</a>
     * If the locale string is <code>null</code> or empty, the platform default locale is assumed. If
     * the localeString matches any locale available per default on the
     * platform, that platform locale is returned. Otherwise the localeString is
     * parsed and the language and country parts are compared against the
     * languages and countries provided by the platform. Any unsupported
     * language or country is replaced by the platform default language and
     * country.
     * Locale string is also parsed for script tag. Alpha String Validation is done to check if the script tag is valid.
     * No default script is set if the script tag is invalid.
     * @param localeString the locale as string
     * @return the {@link Locale} being generated from the {@code localeString}
     */
    static Locale toLocale(String localeString) {
        if (localeString == null || localeString.length() == 0) {
            return Locale.getDefault();
        }
        // support BCP 47 compliant strings as well (using a different separator "-" instead of "_")
        localeString = localeString.replaceAll("-", "_");

        // check language and country
        final String[] parts = localeString.split("_");
        if (parts.length == 0) {
            return Locale.getDefault();
        }

        // at least language is available
        String lang = parts[0];
        boolean isValidLanguageCode = false;
        String[] langs = Locale.getISOLanguages();
        for (int i = 0; i < langs.length; i++) {
            if (langs[i].equalsIgnoreCase(lang)) {
                isValidLanguageCode = true;
                break;
            }
        }
        if (!isValidLanguageCode) {
            lang = Locale.getDefault().getLanguage();
        }

        // only language
        if (parts.length == 1) {
            return new Locale(lang);
        }
    
        // Initialize variables for script, country, and variant
        String script = "";
        String country = "";
        String variant = "";
        
        boolean isValidCountryCode = false;
    
        if (parts.length == 2) {
            if (parts[1].length() == 4 && isValidScriptCode(parts[1])) {
                script = parts[1];
            } else {
                country = parts[1];
            }
        } else if (parts.length == 3) {
            if (parts[1].length() == 4 && isValidScriptCode(parts[1])) { // Script and country
                script = parts[1];
                country = parts[2];
            } else { // Country and variant
                country = parts[1];
                variant = parts[2];
            }
        } else if (parts.length >= 4) {
            if (parts[1].length() == 4 && isValidScriptCode(parts[1])) { // Script and country
                script = parts[1];
                country = parts[2];
                variant = parts[3];
            } else {
                country = parts[1];
                variant = parts[2];
            }
        }
    
        // allow user-assigned codes (https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2#User-assigned_code_elements)
        if (USER_ASSIGNED_COUNTRY_CODES_PATTERN.matcher(country.toLowerCase()).matches()) {
            isValidCountryCode = true;
        } else {
            String[] countries = Locale.getISOCountries();
            for (int i = 0; i < countries.length; i++) {
                if (countries[i].equalsIgnoreCase(country)) {
                    isValidCountryCode = true; // signal ok
                    break;
                }
            }
        }
        
        if (!isValidCountryCode && !country.isEmpty()) {
            country = Locale.getDefault().getCountry();
        }
    
        // Return Locale based on available components
        Locale.Builder builder = new Locale.Builder().setLanguage(lang);
        if (!script.isEmpty()) {
            builder.setScript(script);
        }
        if (!country.isEmpty()) {
            builder.setRegion(country);
        }
        try {
            if (!variant.isEmpty()) {
                builder.setVariant(variant);
            }
        } catch (Exception e) {
            if (!script.isEmpty()) {
                return builder.build();
            }
            // fallback to previous implementation
            return new Locale(lang, country, variant);
        }
        return builder.build();
    }
    
    private static boolean isAlphaString(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
    
    private static boolean isValidScriptCode(String s) {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            if (!isAlphaString(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ---------- internal class

    /**
     * The <code>Key</code> class encapsulates the base name and Locale in a
     * single object that can be used as the key in a <code>HashMap</code>.
     */
    protected static final class Key {

        final String baseName;

        final Locale locale;

        // precomputed hash code, because this will always be used due to
        // this instance being used as a key in a HashMap.
        private final int hashCode;

        Key(final String baseName, final Locale locale) {

            int hc = 0;
            if (baseName != null) {
                hc += 17 * baseName.hashCode();
            }
            if (locale != null) {
                hc += 13 * locale.hashCode();
            }

            this.baseName = baseName;
            this.locale = locale;
            this.hashCode = hc;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            } else if (obj instanceof Key) {
                Key other = (Key) obj;
                return equals(this.baseName, other.baseName) && equals(this.locale, other.locale);
            }

            return false;
        }

        private static boolean equals(Object o1, Object o2) {
            if (o1 == null) {
                if (o2 != null) {
                    return false;
                }
            } else if (!o1.equals(o2)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "Key(" + baseName + ", " + locale + ")";
        }
    }

    /**
     * Registry of the loaded <code>resource bundles</code> and the associated <code>service registrations</code>
     * The <code>ResourceBundleRegistry</code> takes care of the registration/deregistration of the resource bundles as OSGi services.
     * It stores the references to the registered resource bundles and to the associated service registrations.
     */
    private static class ResourceBundleRegistry implements AutoCloseable {
        private final Logger log = LoggerFactory.getLogger(getClass());
        private final BundleContext bundleContext;
        final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<ConcurrentHashMap<Key, Entry>> registrations;

        private static class Entry {
            final JcrResourceBundle resourceBundle;
            final ServiceRegistration<ResourceBundle> serviceRegistration;

            Entry(JcrResourceBundle resourceBundle, ServiceRegistration<ResourceBundle> serviceRegistration) {
                this.resourceBundle = resourceBundle;
                this.serviceRegistration = serviceRegistration;
            }
        }

        ResourceBundleRegistry(BundleContext bundleContext) {
            this.bundleContext = bundleContext;
            this.registrations = new AtomicReference<>(new ConcurrentHashMap<>());
        }

        JcrResourceBundle getResourceBundle(Key key) {
            Entry entry = registrations.get().get(key);
            return entry != null ? entry.resourceBundle : null;
        }

        Collection<JcrResourceBundle> getResourceBundles() {
            return registrations.get().values().stream()
                    .map(e -> e.resourceBundle)
                    .collect(Collectors.toList());
        }

        void registerResourceBundle(Key key, JcrResourceBundle resourceBundle) {
            if (closed.get()) {
                return;
            }
            ServiceRegistration<ResourceBundle> serviceReg =
                    bundleContext.registerService(ResourceBundle.class, resourceBundle, serviceProps(key));
            Entry oldEntry = registrations.get().put(key, new Entry(resourceBundle, serviceReg));
            if (oldEntry != null) {
                oldEntry.serviceRegistration.unregister();
            }
            log.debug(
                    "[ResourceBundleRegistry.updateResourceBundle] Registry updated - Nr of entries: {} - Keys: {}",
                    registrations.get().size(),
                    registrations.get().keySet());
        }

        private static Dictionary<String, Object> serviceProps(Key key) {
            Dictionary<String, Object> serviceProps = new Hashtable<>();
            if (key.baseName != null) {
                serviceProps.put("baseName", key.baseName);
            }
            serviceProps.put("locale", key.locale.toString());
            return serviceProps;
        }

        void unregisterResourceBundle(Key key) {
            if (closed.get()) {
                return;
            }
            Entry oldEntry = registrations.get().remove(key);
            if (oldEntry != null) {
                oldEntry.serviceRegistration.unregister();
            } else {
                log.warn(
                        "[ResourceBundleRegistry.unregisterResourceBundle] Could not find resource bundle service for {}",
                        key);
            }
        }

        void unregisterAll() {
            if (closed.get()) {
                return;
            }
            unregisterAllInternal();
        }

        private void unregisterAllInternal() {
            log.debug(
                    "[ResourceBundleRegistry.clearInternal] Before - Nr of Keys: {} - Keys: {}",
                    registrations.get().size(),
                    registrations.get().keySet());
            ConcurrentHashMap<Key, Entry> oldServiceReg = registrations.getAndSet(new ConcurrentHashMap<>());
            for (Entry entry : oldServiceReg.values()) {
                entry.serviceRegistration.unregister();
            }
            log.debug(
                    "[ResourceBundleRegistry.clearInternal] After - Nr of Keys: {} - Keys: {}",
                    registrations.get().size(),
                    registrations.get().keySet());
        }

        boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                unregisterAllInternal();
            }
        }
    }
}
