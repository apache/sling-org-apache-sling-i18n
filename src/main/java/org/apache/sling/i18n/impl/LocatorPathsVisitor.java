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

import java.util.Iterator;
import java.util.Set;

import org.apache.sling.api.resource.AbstractResourceVisitor;
import org.apache.sling.api.resource.Resource;

/**
 * Visitor implementation for traversing the resources under
 * the locator path
 */
class LocatorPathsVisitor extends AbstractResourceVisitor {
    private final PotentialLanguageRootCheck check;
    private final Set<String> paths;
    private int traverseDepth;

    /**
     * Constructor to prepare visitor
     * 
     * @param check the callback to check the resource for a match
     * @param paths the language 
     * @param traverseDepth the maximum depth to traverse the descendant
     */
    public LocatorPathsVisitor(PotentialLanguageRootCheck check, Set<String> paths) {
        this.check = check;
        this.paths = paths;
    }

    public void accept(Resource res, int traverseDepth) {
        this.traverseDepth = traverseDepth;
        super.accept(res);
    }

    /**
     * Override to stop traversal after the specified depth has
     * been reached
     */
    @Override
    protected void traverseChildren(Iterator<Resource> children) {
        if (this.traverseDepth > 0) {
            // decrement before drilling into children
            this.traverseDepth--;
            super.traverseChildren(children);
            // back to original depth
            this.traverseDepth++;
        }
    }

    /**
     * Check the given resource for a match
     */
    @Override
    protected void visit(Resource res) {
        if (check.isResourceBundle(res)) {
            paths.add(res.getPath());
        }
    }

}
