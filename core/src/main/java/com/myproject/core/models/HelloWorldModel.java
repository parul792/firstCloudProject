/*
 *  Copyright 2015 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.myproject.core.models;

import static org.apache.sling.api.resource.ResourceResolver.PROPERTY_RESOURCE_TYPE;

import javax.annotation.PostConstruct;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import java.util.Optional;

@Model(adaptables = Resource.class)
public class HelloWorldModel {

    @ValueMapValue(name=PROPERTY_RESOURCE_TYPE, injectionStrategy=InjectionStrategy.OPTIONAL)
    @Default(values="No resourceType")
    protected String resourceType;

    @SlingObject
    private Resource currentResource;
    @SlingObject
    private ResourceResolver resourceResolver;

    private String message;

    @PostConstruct
    protected void init() {
        // Walk up the resource tree to find the containing cq:Page node.
        // Uses only Sling APIs (no com.day.cq.wcm.api) so the bundle stays
        // within the AEM Cloud allowed API region at start level 20.
        String currentPagePath = Optional.ofNullable(findContainingPagePath(currentResource))
                .orElse("");

        message = "Hello World!\n"
            + "Resource type is: " + resourceType + "\n"
            + "Current page is:  " + currentPagePath + "\n";
    }

    /**
     * Walks up the Sling resource tree until a node with jcr:primaryType=cq:Page
     * is found, then returns its path. Returns null if none is found.
     */
    private String findContainingPagePath(Resource resource) {
        Resource current = resource;
        while (current != null && !current.getPath().equals("/")) {
            ValueMap props = current.getValueMap();
            if ("cq:Page".equals(props.get("jcr:primaryType", String.class))) {
                return current.getPath();
            }
            current = current.getParent();
        }
        return null;
    }

    public String getMessage() {
        return message;
    }

}
