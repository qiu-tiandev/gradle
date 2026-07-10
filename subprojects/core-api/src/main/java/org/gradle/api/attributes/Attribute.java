/*
 * Copyright 2016 the original author or authors.
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

import org.gradle.api.Named;
import org.gradle.api.internal.attributes.AttributeTypeValidator;
import org.gradle.internal.deprecation.DeprecationLogger;

/**
 * An attribute is a named entity with a type. It is used in conjunction with a {@link AttributeContainer}
 * to provide a type safe container for attributes. This class isn't intended to store the value of an
 * attribute, but only represent the identity of the attribute. It means that an attribute must be immutable
 * and can potentially be pooled. Attributes can be created using the {@link #of(String, Class) factory method}.
 * <p>
 * Supported attribute value types are: {@code String}, {@code Boolean}, any subtype of {@link Number},
 * and any type implementing {@link Named}. {@link #of(String, Class)} throws
 * {@link IllegalAttributeTypeException} for any other type — including plain Java {@link Enum} types
 * that do not implement {@link Named}.
 *
 * @param <T> the type of the named attribute
 *
 * @since 3.3
 */
public class Attribute<T> implements Named {
    private final String name;
    private final Class<T> type;
    private final int hashCode;

    /**
     * Creates a new attribute of the given name with the given type. There's no guarantee that subsequent
     * calls to this method with the same attributes would either return the same instance or different instances
     * of {@link Attribute}, so consumers are required to compare the attributes with the {@link #equals(Object)}
     * method.
     * @param name the name of the attribute
     * @param type the class of the attribute; must be {@code String}, {@code Boolean}, a subtype of
     *             {@link Number}, or a subtype of {@link Named}
     * @param <T> the type of the attribute
     * @return an attribute with the given name and type
     * @throws IllegalAttributeTypeException if {@code type} is not one of the supported attribute value types
     */
    public static <T> Attribute<T> of(String name, Class<T> type) {
        validateSupportedType(name, type);
        return new Attribute<T>(name, type);
    }

    /**
     * Fully-qualified name of a plain enum used as an attribute value type by Kotlin Gradle Plugin
     * versions 2.0.20 and 2.0.21. Newer KGP versions (2.2.0+) do not use this enum. To preserve
     * compatibility with those two specific KGP versions, this exact class name is allowed to pass
     * {@link #validateSupportedType(String, Class)} with a deprecation warning instead of an error.
     * <p>
     * This special case should be removed when compatibility with KGP 2.0.20/2.0.21 is no longer required.
     */
    private static final String KGP_NATIVE_BUNDLE_ENUM_FQN =
        "org.jetbrains.kotlin.gradle.targets.native.toolchain.KotlinNativeBundleArtifactFormat$KotlinNativeBundleArtifactsTypes";

    private static void validateSupportedType(String name, Class<?> type) {
        if (AttributeTypeValidator.isSupportedAttributeType(type)) {
            return;
        }
        if (KGP_NATIVE_BUNDLE_ENUM_FQN.equals(type.getName())) {
            DeprecationLogger.deprecate("Using the enum type KotlinNativeBundleArtifactsTypes as an attribute value type")
                .withContext("This enum does not implement Named. All Enums used as Attribute values should implement Named. This enum type is used by Kotlin Gradle Plugin 2.0.20 and 2.0.21. Upgrade to KGP 2.2.0 or later, in which the plugin no longer uses a plain enum for this attribute.")
                .willBecomeAnErrorInGradle10()
                .withUpgradeGuideSection(9, "kgp_native_bundle_attribute_enum")
                .nagUser();
            return;
        }
        throwIllegalAttributeType(name, type);
    }

    private static void throwIllegalAttributeType(String name, Class<?> type) {
        throw new IllegalAttributeTypeException(name, type);
    }

    /**
     * Creates a new attribute of the given type, inferring the name of the attribute from the simple type name.
     * This method is useful when there's supposedly only one attribute of a specific type in a container, so there's
     * no need to distinguish by name (but the returned type doesn't enforce it). There's no guarantee that subsequent
     * calls to this method with the same attributes would either return the same instance or different instances
     * of {@link Attribute}, so consumers are required to compare the attributes with the {@link #equals(Object)}
     * method.
     * @param type the class of the attribute
     * @param <T> the type of the attribute
     * @return an attribute with the given name and type
     */
    public static <T> Attribute<T> of(Class<T> type) {
        @SuppressWarnings("deprecation")
        String uncapitalizedCanonicalName = org.apache.commons.lang3.text.WordUtils.uncapitalize(type.getCanonicalName());
        return of(uncapitalizedCanonicalName, type);
    }

    private Attribute(String name, Class<T> type) {
        this.name = name;
        this.type = type;
        int hashCode = name.hashCode();
        hashCode = 31 * hashCode + type.getName().hashCode();
        this.hashCode = hashCode;
    }

    /**
     * Returns the name of the attribute.
     * @return the name of the attribute.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Returns the type of this attribute.
     * @return the type of this attribute.
     */
    public Class<T> getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Attribute<?> attribute = (Attribute<?>) o;

        if (!name.equals(attribute.name)) {
            return false;
        }
        return type.equals(attribute.type);

    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return name;
    }


}
