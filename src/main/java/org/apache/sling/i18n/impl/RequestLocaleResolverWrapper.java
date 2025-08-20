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

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.sling.api.wrappers.JakartaToJavaxRequestWrapper;
import org.apache.sling.i18n.JakartaRequestLocaleResolver;
import org.apache.sling.i18n.RequestLocaleResolver;

/**
 * Adapter to convert the a deprecated RequestLocaleResolver object to
 * JakartaRequestLocaleResolver
 * @deprecated use a {@link JakartaRequestLocaleResolver} instead
 */
@Deprecated(since = "3.0.0")
public class RequestLocaleResolverWrapper implements JakartaRequestLocaleResolver {
    private RequestLocaleResolver wrapped;

    public RequestLocaleResolverWrapper(RequestLocaleResolver wrapped) {
        this.wrapped = wrapped;
    }

    public RequestLocaleResolver getWrapped() {
        return wrapped;
    }

    @Override
    public List<Locale> resolveLocale(HttpServletRequest request) {
        javax.servlet.http.HttpServletRequest javaxRequest = JakartaToJavaxRequestWrapper.toJavaxRequest(request);
        return wrapped.resolveLocale(javaxRequest);
    }
}
