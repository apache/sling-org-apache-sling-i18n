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

import java.util.Arrays;
import java.util.Locale;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * Logic to check if a resource is for a resource bundle
 */
class PotentialLanguageRootCheck {
    private final String baseName;
    private final String localeString;
    private final String localeStringLower;
    private final String localeRFC4646String;
    private final String localeRFC4646StringLower;

    public PotentialLanguageRootCheck(String baseName, Locale locale) {
        this.baseName = baseName;
        this.localeString = locale.toString();
        this.localeStringLower = localeString.toLowerCase();
        this.localeRFC4646String = toRFC4646String(locale);
        this.localeRFC4646StringLower = localeRFC4646String.toLowerCase();
    }

    /**
     * Checks if the specified resource is a match for a resource bundle resource
     *
     * @param resource the resource to check
     */
    public boolean isResourceBundle(Resource resource) {
        boolean match = false;
        ValueMap properties = resource.adaptTo(ValueMap.class);
        if (properties != null) {
            String language = properties.get(JcrResourceBundle.PROP_LANGUAGE, String.class);
            if (language != null && language.length() > 0) {
                if (language.equals(localeString)
                        || language.equals(localeStringLower)
                        || language.equals(localeRFC4646String)
                        || language.equals(localeRFC4646StringLower)) {
                    // basename might be a multivalue (see https://issues.apache.org/jira/browse/SLING-4547)
                    String[] baseNames = properties.get(JcrResourceBundle.PROP_BASENAME, new String[] {});
                    if (baseName == null || Arrays.asList(baseNames).contains(baseName)) {
                        match = true;
                    }
                }
            }
        }
        return match;
    }

    // Would be nice if Locale.toString() output RFC 4646, but it doesn't
    private static String toRFC4646String(Locale locale) {
        return locale.toString().replace('_', '-');
    }
}
