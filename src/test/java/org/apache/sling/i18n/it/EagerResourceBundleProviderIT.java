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

import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.OptionUtils;

import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.newConfiguration;

/**
 * Runs the inherited repository-backed tests with eager preloading enabled,
 * complementing the default lazy-loading suite. In particular, targeted fallback
 * updates must rebuild dependent bundles against their refreshed parents.
 */
public class EagerResourceBundleProviderIT extends ResourceBundleProviderIT {

    @Override
    @Configuration
    public Option[] configuration() {
        return OptionUtils.combine(
                super.configuration(),
                newConfiguration("org.apache.sling.i18n.impl.JcrResourceBundleProvider")
                        .put("preload.bundles", true)
                        .asOption());
    }
}
