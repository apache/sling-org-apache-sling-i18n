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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PathFilterTest {

    @Test
    public void testRootIncludeWithPathExclusions() {
        final PathFilter filter = new PathFilter(new String[] {"/"}, new String[] {"/excluded/path"});

        assertFalse(filter.includePath("/excluded/path"));
        assertTrue(filter.includePath("/another/path"));
        assertFalse(filter.includePath("/excluded/path/node"));
    }

    @Test
    public void testIncludesAndExclusions() {
        final PathFilter filter = new PathFilter(new String[] {"/libs", "/apps", "/content"}, new String[] {"/libs/foo"});

        assertFalse(filter.includePath("/another/path"));
        assertFalse(filter.includePath("/libs/foo/i18n"));
        assertTrue(filter.includePath("/libs/app/me"));
        assertTrue(filter.includePath("/apps/app/me"));
        assertTrue(filter.includePath("/content/dam/abc"));
    }
}
