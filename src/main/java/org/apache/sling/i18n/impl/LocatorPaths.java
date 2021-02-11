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

/**
 * Details about locator paths for a bundle
 */
class LocatorPaths {
    private final String path;
    private final int traverseDepth;
    private final long forBundleId;

    public LocatorPaths(String path, int traverseDepth, long forBundleId) {
        this.path = path;
        this.traverseDepth = traverseDepth;
        this.forBundleId = forBundleId;
    }

    public String getPath() {
        return path;
    }

    public int getTraverseDepth() {
        return traverseDepth;
    }

    public long getForBundleId() {
        return forBundleId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (forBundleId ^ (forBundleId >>> 32));
        result = prime * result + ((path == null) ? 0 : path.hashCode());
        result = prime * result + traverseDepth;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        LocatorPaths other = (LocatorPaths) obj;
        if (forBundleId != other.forBundleId)
            return false;
        if (path == null) {
            if (other.path != null)
                return false;
        } else if (!path.equals(other.path))
            return false;
        if (traverseDepth != other.traverseDepth)
            return false;
        return true;
    }

}
