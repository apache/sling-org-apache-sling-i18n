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
import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.i18n.ResourceBundleProvider;
import org.apache.sling.jcr.api.SlingRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class ResourceBundleProviderIT extends I18nTestSupport {

    public static final int RETRY_TIMEOUT_MSEC = 50000;
    public static final String MSG_KEY1 = "foo";
    public static final String MSG_KEY2 = "foo2";
    public static final String MSG_KEY3 = "foo3";

    public static final String BASENAME = "my-basename";

    @Inject
    private SlingRepository repository;

    @Inject
    private BundleContext bundleContext;

    private ServiceTracker<ResourceBundleProvider, ResourceBundleProvider> providerTracker;

    private Session session;
    private Node i18nRoot;
    private Node deRoot;
    private Node deDeRoot;
    private Node frRoot;
    private Node enRoot;
    private Node enBasenameRoot;

    abstract static class Retry {
        Retry(int timeoutMsec) {
            final long timeout = System.currentTimeMillis() + timeoutMsec;
            Throwable lastT = null;
            while (System.currentTimeMillis() < timeout) {
                try {
                    lastT = null;
                    exec();
                    break;
                } catch (Throwable t) {
                    lastT = t;
                }
            }

            if (lastT != null) {
                fail("Failed after " + timeoutMsec + " msec: " + lastT);
            }
        }

        protected abstract void exec() throws Exception;
    }

    @Before
    public void setup() throws RepositoryException {
        // Startup configuration can replace the service after Pax Exam injects test fields.
        providerTracker = new ServiceTracker<>(bundleContext, ResourceBundleProvider.class, null);
        providerTracker.open();
        session = repository.loginAdministrative(null);
        final Node root = session.getRootNode();
        final Node libs;
        if (root.hasNode("libs")) {
            libs = root.getNode("libs");
        } else {
            libs = root.addNode("libs", "nt:unstructured");
        }
        i18nRoot = libs.addNode("i18n", "nt:unstructured");
        deRoot = addLanguageNode(i18nRoot, "de");
        frRoot = addLanguageNode(i18nRoot, "fr");
        deDeRoot = addLanguageNode(i18nRoot, "de_DE");
        enRoot = addLanguageNode(i18nRoot, "en");
        enBasenameRoot = addLanguageNodeWithBasename(i18nRoot, "en", BASENAME);
        session.save();
    }

    @After
    public void cleanup() throws RepositoryException {
        i18nRoot.remove();
        session.save();
        session.logout();
        providerTracker.close();
    }

    private ResourceBundleProvider getProvider() {
        ResourceBundleProvider provider = providerTracker.getService();
        assertNotNull("No active ResourceBundleProvider service", provider);
        return provider;
    }

    private Node addLanguageNode(Node parent, String language) throws RepositoryException {
        final Node child = parent.addNode(language, "sling:Folder");
        child.addMixin("mix:language");
        child.setProperty("jcr:language", language);
        return child;
    }

    private Node addLanguageNodeWithBasename(Node parent, String language, String basename) throws RepositoryException {
        final Node child = parent.addNode(language + "-" + basename, "sling:Folder");
        child.addMixin("mix:language");
        child.setProperty("jcr:language", language);
        if (basename != null) {
            child.setProperty("sling:basename", basename);
        }
        return child;
    }

    private void assertMessage(final String key, final Locale locale, final String basename, final String value) {
        new Retry(RETRY_TIMEOUT_MSEC) {
            @Override
            protected void exec() {
                {
                    final ResourceBundle resourceBundle =
                            getProvider().getResourceBundle(basename, locale); // this is the resource bundle for de_DE
                    assertNotNull(resourceBundle);
                    assertEquals(value, resourceBundle.getString(key));
                }
            }
        };
    }

    private void assertMessages(
            final String key, final String deMessage, final String deDeMessage, final String frMessage) {
        assertMessage(key, Locale.GERMAN, null, deMessage);
        assertMessage(key, Locale.GERMANY, null, deDeMessage);
        assertMessage(key, Locale.FRENCH, null, frMessage);
    }

    private void setMessage(final Node rootNode, final String key, final String message) throws RepositoryException {
        final String nodeName = "node_" + key;
        final Node node;
        if (rootNode.hasNode(nodeName)) {
            node = rootNode.getNode(nodeName);
        } else {
            node = rootNode.addNode(nodeName, "sling:MessageEntry");
        }
        node.setProperty("sling:key", key);
        node.setProperty("sling:message", message);
    }

    @Test
    public void testGetResourceWithBasename() throws RepositoryException {
        // set a key which available in the en dictionary without the basename
        setMessage(enRoot, MSG_KEY1, "regular");
        session.save();
        // default key must be returned, as the one set above did not have the basename
        assertMessage(MSG_KEY1, Locale.ENGLISH, BASENAME, MSG_KEY1);
        setMessage(enBasenameRoot, MSG_KEY1, "overwritten");
        session.save();
        assertMessage(MSG_KEY1, Locale.ENGLISH, BASENAME, "overwritten");
    }

    /**
     * Verifies that repository observation propagates an English fallback update
     * to German, German-country, and French bundles after they have been cached.
     * The nt:unstructured entry is supported by message loading but avoids the
     * sling:MessageEntry detection, covering untyped entries as well.
     * This exercises targeted refresh, where stale parent references would
     * otherwise leave dependent locales returning the old English value.
     */
    @Test
    public void testFallbackChangesForUntypedMessages() throws RepositoryException {
        Node message = enRoot.addNode("fallback", "nt:unstructured");
        message.setProperty("sling:key", MSG_KEY2);
        message.setProperty("sling:message", "old English fallback");
        session.save();
        assertMessages(MSG_KEY2, "old English fallback", "old English fallback", "old English fallback");

        message.setProperty("sling:message", "new English fallback");
        session.save();
        assertMessages(MSG_KEY2, "new English fallback", "new English fallback", "new English fallback");
    }

    /**
     * Exercises repository observation for locale-specific entries, shared English fallbacks,
     * and named dictionaries. Isolated German entry additions, edits, removals, and message-mixin
     * additions must update German and country bundles without replacing English, French, or unrelated named bundles.
     */
    @Test
    public void testChangesDetection() throws RepositoryException {
        // set a key which is only available in the en dictionary
        setMessage(enRoot, MSG_KEY2, "EN_message");
        session.save();

        // since "en" is the fallback for all other resource bundle, the value from "en" must be exposed
        assertMessages(MSG_KEY2, "EN_message", "EN_message", "EN_message");

        setMessage(deRoot, MSG_KEY1, "DE_message");
        setMessage(frRoot, MSG_KEY1, "FR_message");
        session.save();
        assertMessages(MSG_KEY1, "DE_message", "DE_message", "FR_message");

        setMessage(deRoot, MSG_KEY1, "DE_changed");
        setMessage(frRoot, MSG_KEY1, "FR_changed");
        session.save();
        assertMessages(MSG_KEY1, "DE_changed", "DE_changed", "FR_changed");

        setMessage(deRoot, MSG_KEY1, "DE_message");
        setMessage(deDeRoot, MSG_KEY1, "DE_DE_message");
        setMessage(frRoot, MSG_KEY1, "FR_message");
        session.save();
        assertMessages(MSG_KEY1, "DE_message", "DE_DE_message", "FR_message");

        // now change a key which is only available in the "en" dictionary
        setMessage(enRoot, MSG_KEY2, "EN_changed");
        session.save();
        assertMessages(MSG_KEY2, "EN_changed", "EN_changed", "EN_changed");

        // set a message and fetch it so that it is cached in the resourcebundle cache
        setMessage(enBasenameRoot, MSG_KEY3, "EN_basename_message");
        session.save();
        assertMessage(MSG_KEY3, Locale.ENGLISH, null, "EN_basename_message");
        assertMessage(MSG_KEY3, Locale.ENGLISH, BASENAME, "EN_basename_message");

        // see that both resource bundles with and without basename are changed
        setMessage(enBasenameRoot, MSG_KEY3, "EN_basename_changed");
        session.save();
        assertMessage(MSG_KEY3, Locale.ENGLISH, null, "EN_basename_changed");
        assertMessage(MSG_KEY3, Locale.ENGLISH, BASENAME, "EN_basename_changed");

        // Wait for the preceding fallback refresh to reach cached children before checking isolation.
        assertMessages(MSG_KEY3, "EN_basename_changed", "EN_basename_changed", "EN_basename_changed");
        ResourceBundle english = getProvider().getResourceBundle(Locale.ENGLISH);
        ResourceBundle french = getProvider().getResourceBundle(Locale.FRENCH);
        ResourceBundle named = getProvider().getResourceBundle(BASENAME, Locale.GERMAN);
        ResourceBundle german = getProvider().getResourceBundle(Locale.GERMAN);
        ResourceBundle country = getProvider().getResourceBundle(Locale.GERMANY);

        final String isolatedKey = "isolated";
        setMessage(deRoot, isolatedKey, "DE_added");
        session.save();
        assertMessages(isolatedKey, "DE_added", "DE_added", isolatedKey);
        assertNotSame(german, getProvider().getResourceBundle(Locale.GERMAN));
        assertNotSame(country, getProvider().getResourceBundle(Locale.GERMANY));
        assertSame(english, getProvider().getResourceBundle(Locale.ENGLISH));
        assertSame(french, getProvider().getResourceBundle(Locale.FRENCH));
        assertSame(named, getProvider().getResourceBundle(BASENAME, Locale.GERMAN));

        // A property change on an existing sling:MessageEntry must not rediscover every dictionary.
        setMessage(deRoot, isolatedKey, "DE_changed");
        session.save();
        assertMessages(isolatedKey, "DE_changed", "DE_changed", isolatedKey);
        assertSame(english, getProvider().getResourceBundle(Locale.ENGLISH));
        assertSame(french, getProvider().getResourceBundle(Locale.FRENCH));
        assertSame(named, getProvider().getResourceBundle(BASENAME, Locale.GERMAN));

        deRoot.getNode("node_" + isolatedKey).remove();
        session.save();
        assertMessages(isolatedKey, isolatedKey, isolatedKey, isolatedKey);
        assertSame(english, getProvider().getResourceBundle(Locale.ENGLISH));
        assertSame(french, getProvider().getResourceBundle(Locale.FRENCH));
        assertSame(named, getProvider().getResourceBundle(BASENAME, Locale.GERMAN));

        // Re-add the entry using the supported message mixin instead of its primary type.
        Node mixedMessage = deRoot.addNode("node_" + isolatedKey, "nt:unstructured");
        mixedMessage.addMixin("sling:Message");
        mixedMessage.setProperty("sling:key", isolatedKey);
        mixedMessage.setProperty("sling:message", "DE_mixin");
        session.save();
        assertMessages(isolatedKey, "DE_mixin", "DE_mixin", isolatedKey);
        assertSame(english, getProvider().getResourceBundle(Locale.ENGLISH));
        assertSame(french, getProvider().getResourceBundle(Locale.FRENCH));
        assertSame(named, getProvider().getResourceBundle(BASENAME, Locale.GERMAN));
    }

    /**
     * Verifies JSON dictionary discovery, content updates, locale reassignment, and removal
     * against real repository events. JCR reports nt:file content updates at the dictionary
     * root, so these events deliberately retain full refreshes rather than targeted content-child refreshes.
     */
    @Test
    public void testJsonChangesDetection() throws RepositoryException {
        final String key = "json-label";
        assertMessages(key, key, key, key);
        Node dictionary = i18nRoot.addNode("de.json", "nt:file");
        dictionary.addMixin("mix:language");
        dictionary.setProperty("jcr:language", "de");
        Node content = dictionary.addNode("jcr:content", "nt:resource");
        content.setProperty("jcr:mimeType", "application/json");
        setJsonMessage(content, key, "Original");
        session.save();
        assertMessages(key, "Original", "Original", key);

        ResourceBundle english = getProvider().getResourceBundle(Locale.ENGLISH);
        ResourceBundle french = getProvider().getResourceBundle(Locale.FRENCH);
        ResourceBundle named = getProvider().getResourceBundle(BASENAME, Locale.GERMAN);
        List<ResourceChange> observed = new CopyOnWriteArrayList<>();
        Dictionary<String, Object> properties = new Hashtable<>();
        final String dictionaryPath = dictionary.getPath();
        properties.put(ResourceChangeListener.PATHS, dictionaryPath);
        ServiceRegistration<ResourceChangeListener> listener =
                bundleContext.registerService(ResourceChangeListener.class, observed::addAll, properties);
        try {
            setJsonMessage(content, key, "Changed");
            session.save();
            assertMessages(key, "Changed", "Changed", key);
            // The JCR provider rolls nt:file content updates up to the root, retaining full refreshes.
            new Retry(RETRY_TIMEOUT_MSEC) {
                @Override
                protected void exec() {
                    assertTrue(observed.stream()
                            .anyMatch(change -> change.getType() == ResourceChange.ChangeType.CHANGED
                                    && change.getPath().equals(dictionaryPath)));
                }
            };
            assertNotSame(english, getProvider().getResourceBundle(Locale.ENGLISH));
            assertNotSame(french, getProvider().getResourceBundle(Locale.FRENCH));
            assertNotSame(named, getProvider().getResourceBundle(BASENAME, Locale.GERMAN));
        } finally {
            listener.unregister();
        }

        english = getProvider().getResourceBundle(Locale.ENGLISH);
        french = getProvider().getResourceBundle(Locale.FRENCH);
        assertMessage(key, Locale.ITALIAN, null, key);
        dictionary.setProperty("jcr:language", "it");
        session.save();
        assertMessages(key, key, key, key);
        assertMessage(key, Locale.ITALIAN, null, "Changed");
        assertNotSame(english, getProvider().getResourceBundle(Locale.ENGLISH));
        assertNotSame(french, getProvider().getResourceBundle(Locale.FRENCH));

        english = getProvider().getResourceBundle(Locale.ENGLISH);
        french = getProvider().getResourceBundle(Locale.FRENCH);
        dictionary.remove();
        session.save();
        assertMessages(key, key, key, key);
        assertMessage(key, Locale.ITALIAN, null, key);
        assertNotSame(english, getProvider().getResourceBundle(Locale.ENGLISH));
        assertNotSame(french, getProvider().getResourceBundle(Locale.FRENCH));
    }

    private void setJsonMessage(Node content, String key, String message) throws RepositoryException {
        byte[] json = ("{\"" + key + "\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        Binary binary = session.getValueFactory().createBinary(new ByteArrayInputStream(json));
        try {
            content.setProperty("jcr:data", binary);
        } finally {
            binary.dispose();
        }
    }
}
