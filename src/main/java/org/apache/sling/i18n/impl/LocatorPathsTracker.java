/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.i18n.impl;

import java.util.HashSet;
import java.util.Set;

import org.apache.sling.commons.osgi.ManifestHeader;
import org.apache.sling.commons.osgi.ManifestHeader.Entry;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles watching bundles for registration of ResourceBundle locator paths 
 */
class LocatorPathsTracker implements BundleTrackerCustomizer<Set<LocatorPaths>> {

    private static final String CAPABILITY_I18N_RESOURCEBUNDLE_LOCATOR = "org.apache.sling.i18n.resourcebundle.locator";
    private static final String ATTR_PATHS = "paths";
    private static final String ATTR_DEPTH = "depth";

    private Logger log = LoggerFactory.getLogger(getClass());

    private JcrResourceBundleProvider rbp;

    public LocatorPathsTracker(JcrResourceBundleProvider provider) {
        this.rbp = provider;
    }

    @Override
    public Set<LocatorPaths> addingBundle(Bundle bundle, BundleEvent event) {
        log.debug("Considering bundle for registering resource bundle locator paths: {}",
                bundle.getSymbolicName());
        Set<LocatorPaths> pathsSet = null;

        String provideCapability = bundle.getHeaders().get(Constants.PROVIDE_CAPABILITY);
        if (provideCapability != null) {
            ManifestHeader header = ManifestHeader.parse(provideCapability);
            for (Entry entry : header.getEntries()) {
                if (CAPABILITY_I18N_RESOURCEBUNDLE_LOCATOR.equals(entry.getValue())) {
                    String paths = entry.getAttributeValue(ATTR_PATHS);
                    if (paths != null) {
                        if (pathsSet == null) {
                            pathsSet = new HashSet<>();
                        }

                        // used optional depth value is supplied (1 by default)
                        int traversalDepth = 1;
                        String depth = entry.getAttributeValue(ATTR_DEPTH);
                        if (depth != null && !depth.isEmpty()) {
                            traversalDepth = Integer.parseInt(depth);
                        }

                        // treat paths value as a csv
                        String[] items = paths.split(",");
                        for (String path : items) {
                            path = path.trim(); // ignore surrounding spaces
                            if (!path.isEmpty()) {
                                pathsSet.add(new LocatorPaths(path, traversalDepth, bundle.getBundleId()));
                            }
                        }
                    }
                }
            }

            if (pathsSet != null) {
                log.info("Registered {} resource bundle locator paths for bundle: {}",
                        pathsSet.size(), bundle.getSymbolicName());
                this.rbp.registerLocatorPaths(pathsSet);
            }
        }
        return pathsSet;
    }

    @Override
    public void modifiedBundle(Bundle bundle, BundleEvent event, Set<LocatorPaths> baseNamesSet) {
        // no-op
    }

    @Override
    public void removedBundle(Bundle bundle, BundleEvent event, Set<LocatorPaths> baseNamesSet) {
        log.info("Unregistered {} resource bundle locator paths for bundle: {}",
                baseNamesSet.size(), bundle.getSymbolicName());
        this.rbp.unregisterLocatorPaths(baseNamesSet);
    }

}
