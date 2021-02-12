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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * SLING-10135 Test LocatorPaths methods
 */
public class LocatorPathsTest {

    @Test
    public void testGetPath() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);

        assertEquals("/lib/path", paths1.getPath());
    }

    @Test
    public void testGetTraverseDepth() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 2);

        assertEquals(1, paths1.getTraverseDepth());
    }

    @Test
    public void testGetForBundleId() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 2);

        assertEquals(2, paths1.getForBundleId());
    }

    @Test
    public void testEquals() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);
        LocatorPaths paths2 = new LocatorPaths("/lib/path", 1, 1);

        assertEquals(paths1, paths2);
    }

    @Test
    public void testEqualsNullPaths() {
        LocatorPaths paths1 = new LocatorPaths(null, 1, 1);
        LocatorPaths paths2 = new LocatorPaths(null, 1, 1);

        assertEquals(paths1, paths2);
    }

    @Test
    public void testSelfEquals() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);

        assertEquals(paths1, paths1);
    }

    @Test
    public void testNullNotEquals() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);

        assertEquals(false, paths1.equals(null));
    }


    @Test
    public void testHashCode() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);
        LocatorPaths paths2 = new LocatorPaths("/lib/path", 1, 1);

        assertEquals(paths1.hashCode(), paths2.hashCode());
    }

    @Test
    public void testNotEquals() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);

        assertNotEquals(paths1, new Object());
    }

    @Test
    public void testNotEqualsPath() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);
        LocatorPaths paths2 = new LocatorPaths("/lib/path2", 1, 1);

        assertNotEquals(paths1, paths2);
        assertNotEquals(paths1.hashCode(), paths2.hashCode());
    }

    @Test
    public void testNotEqualsNullPath1() {
        LocatorPaths paths1 = new LocatorPaths(null, 1, 1);
        LocatorPaths paths2 = new LocatorPaths("/lib/path2", 1, 1);

        assertNotEquals(paths1, paths2);
        assertNotEquals(paths1.hashCode(), paths2.hashCode());
    }

    @Test
    public void testNotEqualsNullPath2() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);
        LocatorPaths paths2 = new LocatorPaths(null, 1, 1);

        assertNotEquals(paths1, paths2);
        assertNotEquals(paths1.hashCode(), paths2.hashCode());
    }

    @Test
    public void testNotEqualsDepth() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);
        LocatorPaths paths2 = new LocatorPaths("/lib/path", 2, 1);

        assertNotEquals(paths1, paths2);
        assertNotEquals(paths1.hashCode(), paths2.hashCode());
    }

    @Test
    public void testNotEqualsBundleId() {
        LocatorPaths paths1 = new LocatorPaths("/lib/path", 1, 1);
        LocatorPaths paths2 = new LocatorPaths("/lib/path", 1, 2);

        assertNotEquals(paths1, paths2);
        assertNotEquals(paths1.hashCode(), paths2.hashCode());
    }

}
