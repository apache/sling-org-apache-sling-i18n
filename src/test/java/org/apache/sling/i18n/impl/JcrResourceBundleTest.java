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

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.util.TraversingItemVisitor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.jcr.MockJcr;
import org.apache.sling.testing.mock.jcr.MockQueryResult;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the {@link JcrResourceBundle} class.
 */
public class JcrResourceBundleTest {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    protected ResourceResolver resolver;

    private Session getSession() {
        return context.resourceResolver().adaptTo(Session.class);
    }

    @Before
    public void setUp() throws Exception {
        Session session = getSession();
        String[] cndResourcesToLoad = new String[] {
            "/org/apache/jackrabbit/oak/builtin_nodetypes.cnd",
            "/SLING-INF/nodetypes/jcrlanguage.cnd",
            "/SLING-INF/nodetypes/message.cnd"
        };
        for (String resourceName : cndResourcesToLoad) {
            URL cndUrl = getClass().getResource(resourceName);
            if (cndUrl == null) {
                fail("Failed to load CND nodetypes resource: " + resourceName);
            }
            try (Reader reader = new InputStreamReader(cndUrl.openStream())) {
                MockJcr.loadNodeTypeDefs(session, reader);
            }
        }

        resolver = context.resourceResolver();

        createTestContent();

        MockJcr.addQueryResultHandler(session, query -> {
            List<Node> languageNodes = new ArrayList<>();
            try {
                session.getRootNode().accept(new TraversingItemVisitor.Default() {
                    @Override
                    protected void entering(Node node, int level) throws RepositoryException {
                        if (node.isNodeType("mix:language")) {
                            languageNodes.add(node);
                        }
                    }
                });
            } catch (RepositoryException e) {
                fail("Failed to visit language nodes. Reason: " + e.getMessage());
            }
            return new MockQueryResult(languageNodes);
        });
    }

    // test data to add to the repository (use linked hash map for insertion order)
    public static final Map<String, Message> MESSAGES_DE = new LinkedHashMap<String, Message>();
    public static final Map<String, Message> MESSAGES_EN = new LinkedHashMap<String, Message>();
    public static final Map<String, Message> MESSAGES_EN_DASH_US = new LinkedHashMap<String, Message>();
    public static final Map<String, Message> MESSAGES_EN_UNDERSCORE_UK = new LinkedHashMap<String, Message>();
    public static final Map<String, Message> MESSAGES_ZH_UNDERSCORE_HANS_UNDERSCORE_CN =
            new LinkedHashMap<String, Message>();
    public static final Map<String, Message> MESSAGES_EN_UNDERSCORE_AU = new LinkedHashMap<String, Message>();
    public static final Map<String, Message> MESSAGES_DE_APPS = new LinkedHashMap<String, Message>();
    public static final Map<String, Message> MESSAGES_DE_BASENAME = new LinkedHashMap<String, Message>();

    public static void add(Map<String, Message> map, Message msg) {
        map.put(msg.key, msg);
    }

    public static final Message PARENT_MSG = new Message("", "untranslated", "means: not translated", false);

    // create test data
    static {
        // 1. direct child node of language node, using sling:key
        add(MESSAGES_DE, new Message("", "kitchen", "K�che", false));
        // 2. direct child node of language node, using nodename
        add(MESSAGES_DE, new Message("", "plate", "Teller", true));
        // 3. nested node, using sling:key
        add(MESSAGES_DE, new Message("f", "fork", "Gabel", false));
        // 4. nested node, using nodename
        add(MESSAGES_DE, new Message("s/p/o", "spoon", "L�ffel", true));

        // 5. not present in DE
        add(MESSAGES_EN, PARENT_MSG);

        add(MESSAGES_EN_DASH_US, new Message("", "pigment", "color", false));
        add(MESSAGES_EN_UNDERSCORE_UK, new Message("", "pigment", "colour", false));
        add(MESSAGES_EN_UNDERSCORE_AU, new Message("", "pigment", "colour", false));

        add(MESSAGES_ZH_UNDERSCORE_HANS_UNDERSCORE_CN, new Message("", "pigment", "颜料", false));

        // 6. same as 1.-4., but different translations for overwriting into apps
        for (Message msg : MESSAGES_DE.values()) {
            add(MESSAGES_DE_APPS, new Message(msg.path, msg.key, "OTHER", msg.useNodeName));
        }

        // 7. same as 1.-4., but different translations for different sling:basename
        for (Message msg : MESSAGES_DE.values()) {
            add(MESSAGES_DE_BASENAME, new Message(msg.path, msg.key, "BASENAME", msg.useNodeName));
        }
    }

    public void createTestContent() throws Exception {
        Node i18n =
                getSession().getRootNode().addNode("libs", "nt:unstructured").addNode("i18n", "nt:unstructured");

        // some DE content
        Node de = i18n.addNode("de", "nt:folder");
        de.addMixin("mix:language");
        de.setProperty("jcr:language", "de");
        for (Message msg : MESSAGES_DE.values()) {
            msg.add(de);
        }
        getSession().save();

        // some EN content (for parent bundling)
        Node en = i18n.addNode("en", "nt:folder");
        en.addMixin("mix:language");
        en.setProperty("jcr:language", "en");
        for (Message msg : MESSAGES_EN.values()) {
            msg.add(en);
        }
        getSession().save();

        // some EN US content
        Node enDashUS = i18n.addNode("en-US", "nt:folder");
        enDashUS.addMixin("mix:language");
        enDashUS.setProperty("jcr:language", "en-US");
        for (Message msg : MESSAGES_EN_DASH_US.values()) {
            msg.add(enDashUS);
        }
        getSession().save();

        // some EN UK content
        Node enUnderscoreUK = i18n.addNode("en_UK", "nt:folder");
        enUnderscoreUK.addMixin("mix:language");
        enUnderscoreUK.setProperty("jcr:language", "en_UK");
        for (Message msg : MESSAGES_EN_UNDERSCORE_UK.values()) {
            msg.add(enUnderscoreUK);
        }
        getSession().save();

        // some EN AU content
        Node enUnderscoreAU = i18n.addNode("en_au", "nt:folder");
        enUnderscoreAU.addMixin("mix:language");
        enUnderscoreAU.setProperty("jcr:language", "en_au");
        for (Message msg : MESSAGES_EN_UNDERSCORE_AU.values()) {
            msg.add(enUnderscoreAU);
        }
        getSession().save();

        // some zh_hans_cn content
        Node zhUnderscoreHansUnderscoreCN = i18n.addNode("zh_hans_cn", "nt:folder");
        zhUnderscoreHansUnderscoreCN.addMixin("mix:language");
        zhUnderscoreHansUnderscoreCN.setProperty("jcr:language", "zh_hans_cn");
        for (Message msg : MESSAGES_ZH_UNDERSCORE_HANS_UNDERSCORE_CN.values()) {
            msg.add(zhUnderscoreHansUnderscoreCN);
        }
    }

    // ---------------------------------------------------------------< tests >

    @Test
    public void test_getString() {
        JcrResourceBundle bundle = new JcrResourceBundle(new Locale("de"), null, resolver, null, new PathFilter());
        for (Message msg : MESSAGES_DE.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        bundle = new JcrResourceBundle(new Locale("en", "us"), null, resolver, null, new PathFilter());
        for (Message msg : MESSAGES_EN_DASH_US.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        bundle = new JcrResourceBundle(new Locale("en", "uk"), null, resolver, null, new PathFilter());
        for (Message msg : MESSAGES_EN_UNDERSCORE_UK.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        bundle = new JcrResourceBundle(new Locale("en", "au"), null, resolver, null, new PathFilter());
        for (Message msg : MESSAGES_EN_UNDERSCORE_AU.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        bundle = new JcrResourceBundle(
                new Locale.Builder()
                        .setLanguage(Locale.CHINA.getLanguage())
                        .setRegion(Locale.CHINA.getCountry())
                        .setScript("hans")
                        .build(),
                null,
                resolver,
                null,
                new PathFilter());
        for (Message msg : MESSAGES_ZH_UNDERSCORE_HANS_UNDERSCORE_CN.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }
    }

    @Test
    public void test_getObject() {
        JcrResourceBundle bundle = new JcrResourceBundle(new Locale("de"), null, resolver, null, new PathFilter());
        for (Message msg : MESSAGES_DE.values()) {
            assertEquals(msg.message, (String) bundle.getObject(msg.key));
        }
    }

    @Test
    public void test_handle_missing_key() {
        // test if key is returned if no entry found in repo
        JcrResourceBundle bundle = new JcrResourceBundle(new Locale("de"), null, resolver, null, new PathFilter());
        bundle.setParent(new RootResourceBundle());
        assertEquals("missing", bundle.getString("missing"));
    }

    @Test
    public void test_getKeys() {
        JcrResourceBundle bundle = new JcrResourceBundle(new Locale("de"), null, resolver, null, new PathFilter());
        Enumeration<String> keys = bundle.getKeys();
        int counter = 0;
        while (keys.hasMoreElements()) {
            counter++;
            String key = keys.nextElement();
            assertTrue("bundle returned key that is not supposed to be there: " + key, MESSAGES_DE.containsKey(key));
        }
        assertEquals(MESSAGES_DE.size(), counter);
    }

    @Test
    public void test_bundle_parenting() {
        // set parent of resource bundle, test if passed through
        JcrResourceBundle bundle = new JcrResourceBundle(new Locale("de"), null, resolver, null, new PathFilter());
        JcrResourceBundle parentBundle =
                new JcrResourceBundle(new Locale("en"), null, resolver, null, new PathFilter());
        bundle.setParent(parentBundle);
        parentBundle.setParent(new RootResourceBundle());

        assertEquals(PARENT_MSG.message, bundle.getObject(PARENT_MSG.key));
        assertEquals("missing", bundle.getString("missing"));
    }

    @Test
    public void test_search_path() throws Exception {
        // overwrite stuff in apps
        Node appsI18n = getSession().getRootNode().addNode("apps").addNode("i18n", "nt:unstructured");
        Node de = appsI18n.addNode("de", "nt:folder");
        de.addMixin("mix:language");
        de.setProperty("jcr:language", "de");
        for (Message msg : MESSAGES_DE_APPS.values()) {
            msg.add(de);
        }
        getSession().save();

        // test getString
        JcrResourceBundle bundle = new JcrResourceBundle(new Locale("de"), null, resolver, null, new PathFilter());
        for (Message msg : MESSAGES_DE_APPS.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        // test getKeys
        Enumeration<String> keys = bundle.getKeys();
        int counter = 0;
        while (keys.hasMoreElements()) {
            counter++;
            String key = keys.nextElement();
            assertTrue(
                    "bundle returned key that is not supposed to be there: " + key, MESSAGES_DE_APPS.containsKey(key));
        }
        assertEquals(MESSAGES_DE.size(), counter);
    }

    @Test
    public void test_outside_search_path() throws Exception {
        Node libsI18n = getSession().getRootNode().getNode("libs/i18n");
        libsI18n.remove();

        // dict outside search path: /content
        Node contentI18n = getSession().getRootNode().addNode("content").addNode("i18n", "nt:unstructured");
        Node de = contentI18n.addNode("de", "nt:folder");
        de.addMixin("mix:language");
        de.setProperty("jcr:language", "de");
        for (Message msg : MESSAGES_DE.values()) {
            msg.add(de);
        }
        getSession().save();

        // test if /content dictionary is read at all
        JcrResourceBundle bundle = new JcrResourceBundle(new Locale("de"), null, resolver, null, new PathFilter());
        for (Message msg : MESSAGES_DE.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        // now overwrite /content dict in /libs
        libsI18n = getSession().getRootNode().getNode("libs").addNode("i18n", "nt:unstructured");
        de = libsI18n.addNode("de", "nt:folder");
        de.addMixin("mix:language");
        de.setProperty("jcr:language", "de");
        for (Message msg : MESSAGES_DE_APPS.values()) {
            msg.add(de);
        }
        getSession().save();

        // test if /libs (something in the search path) overlays /content (outside the search path)
        bundle = new JcrResourceBundle(new Locale("de"), null, resolver, null, new PathFilter());
        for (Message msg : MESSAGES_DE_APPS.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        // test getKeys
        Enumeration<String> keys = bundle.getKeys();
        int counter = 0;
        while (keys.hasMoreElements()) {
            counter++;
            String key = keys.nextElement();
            assertTrue(
                    "bundle returned key that is not supposed to be there: " + key, MESSAGES_DE_APPS.containsKey(key));
        }
        assertEquals(MESSAGES_DE.size(), counter);
    }

    @Test
    public void test_basename() throws Exception {
        // create another de lib with a basename set
        Node appsI18n = getSession().getRootNode().getNode("libs/i18n");
        Node de = appsI18n.addNode("de_basename", "nt:unstructured");
        de.addMixin("mix:language");
        de.setProperty("jcr:language", "de");
        de.setProperty("sling:basename", new String[] {"FOO", "BAR"});
        for (Message msg : MESSAGES_DE_BASENAME.values()) {
            msg.add(de);
        }
        getSession().save();

        // test getString
        JcrResourceBundle bundle = new JcrResourceBundle(new Locale("de"), "FOO", resolver, null, new PathFilter());
        for (Message msg : MESSAGES_DE_BASENAME.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        // test getString
        bundle = new JcrResourceBundle(new Locale("de"), "BAR", resolver, null, new PathFilter());
        for (Message msg : MESSAGES_DE_BASENAME.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        // test getKeys
        Enumeration<String> keys = bundle.getKeys();
        int counter = 0;
        while (keys.hasMoreElements()) {
            counter++;
            String key = keys.nextElement();
            assertTrue(
                    "bundle returned key that is not supposed to be there: " + key,
                    MESSAGES_DE_BASENAME.containsKey(key));
        }
        assertEquals(MESSAGES_DE.size(), counter);
    }

    @Test
    public void test_json_dictionary() throws Exception {
        Node appsI18n = getSession().getRootNode().addNode("apps").addNode("i18n", "nt:unstructured");
        Node deJson = appsI18n.addNode("de.json", "nt:file");
        deJson.addMixin("mix:language");
        deJson.setProperty("jcr:language", "de");
        Node content = deJson.addNode("jcr:content", "nt:resource");
        content.setProperty("jcr:mimeType", "application/json");

        // manually creating json file, good enough for the test
        StringBuilder json = new StringBuilder();
        json.append("{");
        for (Message msg : MESSAGES_DE_APPS.values()) {
            json.append("\"").append(msg.key).append("\": \"");
            json.append(msg.message).append("\",\n");
        }
        json.append("}");

        InputStream stream = new ByteArrayInputStream(json.toString().getBytes());
        Binary binary = getSession().getValueFactory().createBinary(stream);
        content.setProperty("jcr:data", binary);
        getSession().save();

        // test getString
        JcrResourceBundle bundle = new JcrResourceBundle(new Locale("de"), null, resolver, null, new PathFilter());
        for (Message msg : MESSAGES_DE_APPS.values()) {
            assertEquals(msg.message, bundle.getString(msg.key));
        }

        // test getKeys
        Enumeration<String> keys = bundle.getKeys();
        int counter = 0;
        while (keys.hasMoreElements()) {
            counter++;
            String key = keys.nextElement();
            assertTrue(
                    "bundle returned key that is not supposed to be there: " + key, MESSAGES_DE_APPS.containsKey(key));
        }
        assertEquals(MESSAGES_DE.size(), counter);
    }
}
