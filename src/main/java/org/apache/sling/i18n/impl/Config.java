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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name ="Apache Sling I18N Resource Bundle Provider",
        description ="ResourceBundleProvider service which loads the messages from the repository.")
public @interface Config {

    @AttributeDefinition(name = "Default Locale",
        description = "The default locale to assume if none can be "+
             "resolved otherwise. This value must be in the form acceptable to the "+
             "java.util.Locale class.")
    String locale_default() default "en";

    @AttributeDefinition(name = "Preload Bundles",
            description = "Whether or not to eagerly load the resource bundles "+
                "on bundle start or a cache invalidation.")
    boolean preload_bundles() default false;

    @AttributeDefinition(name = "Invalidation Delay",
            description = "In case of dictionary change events the cached "+
                    "resource bundle becomes invalid after the given delay (in ms). ")
    long invalidation_delay() default 5000;
    
    @AttributeDefinition(name="Included paths",
            description="Translations in paths starting with one of these values will be included, unless they match one of the excluded paths.")
    String[] included_paths() default {"/libs", "/apps", "/content"};

    @AttributeDefinition(name="Excluded paths",
            description="Translations in paths starting with one of these values will be excluded.")
    String[] excluded_paths() default {"/var/eventing"};
}