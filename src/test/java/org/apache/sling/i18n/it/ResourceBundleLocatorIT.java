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
package org.apache.sling.i18n.it;

import javax.inject.Inject;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimap;
import org.apache.commons.io.IOUtils;
import org.apache.sling.i18n.ResourceBundleProvider;
import org.apache.sling.jcr.api.SlingRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.sling.testing.paxexam.SlingOptions.slingBundleresource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.streamBundle;
import static org.ops4j.pax.tinybundles.core.TinyBundles.withBnd;

/**
 * Tests for SLING-10135 for locating resource bundle resources
 * that are not stored in the JCR repository
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class ResourceBundleLocatorIT extends I18nTestSupport {
    private final Logger logger = LoggerFactory.getLogger(ResourceBundleLocatorIT.class);

    public static final String MSG_KEY1 = "hello";
    public static final String MSG_KEY2 = "test1";

    public static final String BASENAME0 = "org.apache.sling.i18n.testing0.Resources";
    public static final String BASENAME1 = "org.apache.sling.i18n.testing1.Resources";
    public static final String BASENAME2 = "org.apache.sling.i18n.testing2.Resources";
    public static final String BASENAME3 = "org.apache.sling.i18n.testing3.Resources";
    public static final String BASENAME4 = "org.apache.sling.i18n.testing4.Resources";
    public static final String BASENAME5 = "org.apache.sling.i18n.testing5.Resources";
    public static final String BASENAME6 = "org.apache.sling.i18n.testing6.Resources";

    @Inject
    private SlingRepository repository;

    @Inject
    private ResourceBundleProvider resourceBundleProvider;

    private Session session;

    @Override
    @Configuration
    public Option[] configuration() {
        // create 4 tiny bundles with different configurations for testing
        Option[] bundle = new Option[7];
        for (int i = 0; i <= 6; i++) {
            String bundleSymbolicName = String.format("TEST-I18N-BUNDLE-%d", i);

            String baseName = String.format("org.apache.sling.i18n.testing%d.Resources", i);

            String traversePath;
            if (i == 5) {
                traversePath = "";
            } else if (i == 6) {
                traversePath = null;
            } else {
                traversePath = String.format("/libs/i18n/testing%d", i); // NOSONAR
            }
            String resourcePath;
            if (i <= 1) {
                resourcePath = String.format("/libs/i18n/testing%d", i); // NOSONAR
            } else if (i == 2) {
                resourcePath = String.format("/libs/i18n/testing%d/folder1", i); // NOSONAR
            } else {
                resourcePath = String.format("/libs/i18n/testing%d/folder1/folder2", i); // NOSONAR
            }
            String pathInBundle = String.format("SLING-INF%s", resourcePath);
            final Multimap<String, String> content = ImmutableListMultimap.of(
                    pathInBundle, "Resources.json",
                    pathInBundle, "Resources.json.props",
                    pathInBundle, "Resources_en_CA.json",
                    pathInBundle, "Resources_en_CA.json.props");
            int traverseDepth;
            if (i < 4) {
                traverseDepth = i;
            } else {
                traverseDepth = 1;
            }
            try {
                bundle[i] = buildContentBundle(
                        bundleSymbolicName, pathInBundle, resourcePath, traversePath, traverseDepth, content, baseName);
            } catch (IOException e) {
                throw new RuntimeException("Failed to build the content bundle", e);
            }
        }

        return composite(composite(super.configuration()), slingBundleresource(), composite(bundle))
                .getOptions();
    }

    /**
     * Add content to our test bundle
     */
    protected void addContent(final TinyBundle bundle, String pathInBundle, String resourcePath, Object... args)
            throws IOException {
        pathInBundle += "/" + resourcePath;
        resourcePath = "/test-content/" + resourcePath;
        try (final InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull("Expecting resource to be found:" + resourcePath, is);
            logger.info("Adding resource to bundle, path={}, resource={}", pathInBundle, resourcePath);
            if (args != null) {
                String value = IOUtils.toString(is, StandardCharsets.UTF_8);
                value = String.format(value, args);
                try (final InputStream valueStream = new ByteArrayInputStream(value.getBytes())) {
                    bundle.add(pathInBundle, valueStream);
                }
            } else {
                bundle.add(pathInBundle, is);
            }
        }
    }

    protected Option buildContentBundle(
            String bundleSymbolicName,
            String pathInBundle,
            String resourcePath,
            String traversePath,
            int traverseDepth,
            final Multimap<String, String> content,
            String basename)
            throws IOException {
        final TinyBundle bundle = TinyBundles.bundle();
        bundle.set(Constants.BUNDLE_SYMBOLICNAME, bundleSymbolicName);
        bundle.set(
                Constants.REQUIRE_CAPABILITY,
                "osgi.extender;filter:=\"(&(osgi.extender=org.apache.sling.i18n.resourcebundle.locator.registrar)(version<=1.0.0)(!(version>=2.0.0)))\"");
        if (traverseDepth <= 0) {
            if (traversePath == null) {
                bundle.set(Constants.PROVIDE_CAPABILITY, "org.apache.sling.i18n.resourcebundle.locator");
            } else {
                bundle.set(
                        Constants.PROVIDE_CAPABILITY,
                        String.format("org.apache.sling.i18n.resourcebundle.locator;paths=\"%s\"", traversePath));
            }
        } else {
            if (traversePath == null) {
                bundle.set(
                        Constants.PROVIDE_CAPABILITY,
                        String.format("org.apache.sling.i18n.resourcebundle.locator;depth=%d", traverseDepth));
            } else {
                bundle.set(
                        Constants.PROVIDE_CAPABILITY,
                        String.format(
                                "org.apache.sling.i18n.resourcebundle.locator;paths=\"%s\";depth=%d",
                                traversePath, traverseDepth));
            }
        }
        bundle.set("Sling-Bundle-Resources", String.format("%s;path:=%s;propsJSON:=props", resourcePath, pathInBundle));

        for (final Map.Entry<String, String> entry : content.entries()) {
            String entryPathInBundle = entry.getKey();
            String entryResourcePath = entry.getValue();
            if (entryResourcePath.endsWith(".props")) {
                // content is a template so we need to pass the args to replace the placeholder tokens
                addContent(bundle, entryPathInBundle, entryResourcePath, basename);
            } else {
                // content is not a template, so no need to pass any args
                addContent(bundle, entryPathInBundle, entryResourcePath);
            }
        }
        return streamBundle(bundle.build(withBnd())).start();
    }

    @Before
    public void setup() throws RepositoryException {
        session = repository.loginAdministrative(null);
    }

    @After
    public void cleanup() throws RepositoryException {
        session.logout();
    }

    private void assertMessage(final String key, final Locale locale, final String basename, final String value) {
        final ResourceBundle resourceBundle = resourceBundleProvider.getResourceBundle(basename, locale);
        assertNotNull(resourceBundle);
        assertEquals(value, resourceBundle.getString(key));
    }

    @Test
    public void testLocatedResourceBundleDepthNotSpecified() throws RepositoryException {
        assertMessage(MSG_KEY1, Locale.ENGLISH, BASENAME0, "World");
        assertMessage(MSG_KEY1, Locale.CANADA, BASENAME0, "Canada");
    }

    @Test
    public void testLocatedResourceBundleDepth1() throws RepositoryException {
        assertMessage(MSG_KEY1, Locale.ENGLISH, BASENAME1, "World");
        assertMessage(MSG_KEY1, Locale.CANADA, BASENAME1, "Canada");
    }

    @Test
    public void testLocatedResourceBundleDepth2() throws RepositoryException {
        assertMessage(MSG_KEY1, Locale.ENGLISH, BASENAME2, "World");
        assertMessage(MSG_KEY1, Locale.CANADA, BASENAME2, "Canada");
    }

    @Test
    public void testLocatedResourceBundleDepth3() throws RepositoryException {
        assertMessage(MSG_KEY1, Locale.ENGLISH, BASENAME3, "World");
        assertMessage(MSG_KEY1, Locale.CANADA, BASENAME3, "Canada");
    }

    @Test
    public void testNotLocatedResourceBundle() throws RepositoryException {
        assertMessage(MSG_KEY1, Locale.ENGLISH, BASENAME4, MSG_KEY1);
        assertMessage(MSG_KEY1, Locale.CANADA, BASENAME4, MSG_KEY1);
    }

    @Test
    public void testNotLocatedResourceBundleEmptyLocatorPath() throws RepositoryException {
        assertMessage(MSG_KEY1, Locale.ENGLISH, BASENAME5, MSG_KEY1);
        assertMessage(MSG_KEY1, Locale.CANADA, BASENAME5, MSG_KEY1);
    }

    @Test
    public void testNotLocatedResourceBundleNotSpecifiedLocatorPath() throws RepositoryException {
        assertMessage(MSG_KEY1, Locale.ENGLISH, BASENAME6, MSG_KEY1);
        assertMessage(MSG_KEY1, Locale.CANADA, BASENAME6, MSG_KEY1);
    }
}
