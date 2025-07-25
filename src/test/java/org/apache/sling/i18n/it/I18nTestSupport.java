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

import org.apache.sling.testing.paxexam.TestSupport;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.ModifiableCompositeOption;
import org.ops4j.pax.exam.options.extra.VMOption;

import static org.apache.sling.testing.paxexam.SlingOptions.logback;
import static org.apache.sling.testing.paxexam.SlingOptions.slingQuickstartOakTar;
import static org.apache.sling.testing.paxexam.SlingOptions.versionResolver;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.vmOption;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.factoryConfiguration;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.newConfiguration;

public abstract class I18nTestSupport extends TestSupport {

    @Configuration
    public Option[] configuration() {
        // SLING-12312 - newer version of sling.api and dependencies
        //   may remove at a later date if the superclass includes these versions or later
        versionResolver.setVersionFromProject("org.apache.sling", "org.apache.sling.api");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.engine", "3.0.0");
        versionResolver.setVersion("org.apache.felix", "org.apache.felix.http.servlet-api", "6.1.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.resourceresolver", "2.0.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.auth.core", "2.0.0");
        versionResolver.setVersion("commons-fileupload", "commons-fileupload", "1.6.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.scripting.spi", "2.0.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.scripting.core", "3.0.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.servlets.resolver", "3.0.0");
        // also need the 2.x version of slf4j and later logback
        versionResolver.setVersion("org.slf4j", "slf4j-api", "2.0.17");
        versionResolver.setVersion("org.slf4j", "jcl-over-slf4j", "2.0.17");
        versionResolver.setVersion("ch.qos.logback", "logback-core", "1.5.18");
        versionResolver.setVersion("ch.qos.logback", "logback-classic", "1.5.18");

        return options(
                baseConfiguration(),
                quickstart(),
                logback(),
                // Sling I18N
                testBundle("bundle.filename"),
                factoryConfiguration("org.apache.sling.jcr.repoinit.RepositoryInitializer")
                        .put("scripts", new String[] {
                            "create service user sling-i18n\n\n  set ACL for sling-i18n\n\n    allow   jcr:read    on /\n\n  end"
                        })
                        .asOption(),
                factoryConfiguration("org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended")
                        .put("user.mapping", new String[] {"org.apache.sling.i18n=sling-i18n"})
                        .asOption(),
                // testing
                newConfiguration("org.apache.sling.jcr.base.internal.LoginAdminWhitelist")
                        .put("whitelist.bundles.regexp", "PAXEXAM-PROBE-.*")
                        .asOption(),
                // SLING-12312 - begin extra bundles for sling api 3.x
                mavenBundle()
                        .groupId("org.apache.felix")
                        .artifactId("org.apache.felix.http.wrappers")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.apache.sling")
                        .artifactId("org.apache.sling.commons.johnzon")
                        .version("2.0.0"),
                // end extra bundles for sling api 3.x
                junitBundles(),
                optionalRemoteDebug(),
                optionalJacocoCommand());
    }

    protected Option quickstart() {
        final int httpPort = findFreePort();
        final String workingDirectory = workingDirectory();
        return composite(slingQuickstartOakTar(workingDirectory, httpPort));
    }

    /**
     * Optionally configure jacoco vmOption supplied by the "jacoco.command"
     * system property.
     */
    protected ModifiableCompositeOption optionalJacocoCommand() {
        VMOption option = null;
        String property = System.getProperty("jacoco.command");
        if (property != null) {
            option = vmOption(property);
        }
        return composite(option);
    }

    /**
     * Optionally configure remote debugging on the port supplied by the "debugPort"
     * system property.
     */
    protected ModifiableCompositeOption optionalRemoteDebug() {
        VMOption option = null;
        String property = System.getProperty("debugPort");
        if (property != null) {
            option = vmOption(String.format("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=%s", property));
        }
        return composite(option);
    }
}
