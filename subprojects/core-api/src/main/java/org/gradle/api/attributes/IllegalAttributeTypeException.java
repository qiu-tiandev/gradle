/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.attributes;

import org.gradle.api.Incubating;
import org.gradle.api.Named;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.internal.exceptions.ResolutionProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thrown by {@link Attribute#of(String, Class)} when the requested attribute value type is not
 * one of the supported types: {@code String}, {@code Boolean}, a subtype of {@link Number}, or a
 * subtype of {@link Named}.
 * <p>
 * The exception always provides a resolution linking to the variant-attributes documentation.
 * When the offending type is a plain Java {@link Enum} that does not implement {@link Named}, an
 * additional resolution suggesting that the enum implement {@link Named} is provided first.
 *
 * @since 9.8.0
 */
@Incubating
public final class IllegalAttributeTypeException extends IllegalArgumentException implements ResolutionProvider {
    private final List<String> resolutions;

    /**
     * Creates a new exception.
     *
     * @param attributeName the name of the attribute
     * @param type the invalid type used for the attribute
     * @since 9.8.0
     */
    public IllegalAttributeTypeException(String attributeName, Class<?> type) {
        super(buildMessage(attributeName, type));
        this.resolutions = buildResolutions(type);
    }

    private static String buildMessage(String attributeName, Class<?> type) {
        return String.format(
            "Unsupported type '%s' for attribute '%s'. Attribute values must be of type String, Boolean, a subtype of Number, or implement %s.",
            type.getName(), attributeName, Named.class.getName()
        );
    }

    private static List<String> buildResolutions(Class<?> type) {
        List<String> list = new ArrayList<>();
        if (type.isEnum()) {
            list.add(String.format(
                "Have the enum type '%s' implement '%s' by delegating 'getName()' to the built-in 'name()' method.",
                type.getName(), Named.class.getName()
            ));
        }
        list.add("See the documentation on variant attributes at " + new DocumentationRegistry().getDocumentationFor("variant_attributes") + ".");
        return Collections.unmodifiableList(list);
    }

    @Override
    public List<String> getResolutions() {
        return resolutions;
    }
}
