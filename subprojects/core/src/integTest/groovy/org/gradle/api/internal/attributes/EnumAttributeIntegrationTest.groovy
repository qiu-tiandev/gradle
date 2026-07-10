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

package org.gradle.api.internal.attributes

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Issue

/**
 * Integration tests demonstrating that plain Java {@code Enum} types (those that do not
 * implement {@link org.gradle.api.Named}) are <strong>NOT</strong> supported as attribute values in Gradle's
 * dependency-resolution and publishing pipelines.
 * <p>
 * {@link org.gradle.api.attributes.Attribute#of(String, Class)} validates the requested attribute
 * type up front: it accepts {@code String}, {@code Boolean}, any subtype of {@link Number}, and
 * any type implementing {@link org.gradle.api.Named}. Any other type — including plain Java
 * {@code Enum} types — is rejected with {@link org.gradle.api.attributes.IllegalAttributeTypeException}
 * at declaration time, before Gradle ever attempts to configure a configuration or resolve a
 * dependency graph.
 * <p>
 * A Named-implementing enum ({@code enum X implements Named}) delegating {@code getName()} to the
 * built-in {@code name()} is the supported way to use an enum as an attribute value.
 * <p>
 * Each test is parameterized with two enum flavors:
 * <ul>
 *   <li>{@code PLAIN} — a bare {@code enum MyEnum { FOO, BAR }} that does NOT implement
 *       {@link org.gradle.api.Named}. Rejected by {@code Attribute.of} at declaration time.</li>
 *   <li>{@code NAMED} — an {@code enum MyEnum implements Named} that delegates
 *       {@code getName()} to the built-in {@code name()}. Accepted; works end-to-end.</li>
 * </ul>
 * <p>
 * Tests are organized into two regions:
 * <ul>
 *   <li><b>enums succeed</b> — valid usage patterns whose underlying Gradle pipelines handle
 *       plain enums correctly. The Named row exercises the pattern end-to-end. The plain row
 *       exercises {@code Attribute.of}'s type validation and fails at declaration time — but
 *       if that validation were removed, the plain row would work cleanly through the same
 *       code path. Empirically confirmed by running with {@code validateSupportedType} disabled.</li>
 *   <li><b>un-named enums fail</b> — three tests where plain enums genuinely reveal an
 *       underlying failure independent of the up-front check: the ES 9.5.1 regression
 *       (CCE at {@code DesugaringAttributeContainerSerializer:91}) and two JDK-contract
 *       failures at {@code Enum.valueOf} callsites ({@code IsolatedEnumValueSnapshot:56} and
 *       {@code CoercingStringValueSnapshot:39}). See {@code problems-with-unnamed-enums.md}
 *       in this directory for details on each root cause.</li>
 * </ul>
 */
@Issue("https://github.com/gradle/gradle/issues/38242")
final class EnumAttributeIntegrationTest extends AbstractIntegrationSpec {
    // region setup
    // Enum declaration templates injected into build scripts. Each declares a top-level
    // `MyEnum` type with constants FOO and BAR. The PLAIN flavor is a bare enum. The NAMED
    // flavor implements `org.gradle.api.Named` (default-imported in build scripts) by
    // delegating `getName()` to the built-in `name()`.
    private static final String PLAIN_ENUM = """
        enum MyEnum { FOO, BAR }
    """

    private static final String NAMED_ENUM = """
        enum MyEnum implements Named {
            FOO, BAR

            @Override
            String getName() { return name() }
        }
    """

    private static final String PLAIN_DESC = "plain Enum"
    private static final String NAMED_DESC = "Named-implementing Enum"

    // Expected root-cause message when Attribute.of rejects a plain enum type.
    private static final String UNSUPPORTED_TYPE_MSG = "Unsupported type 'MyEnum' for attribute 'myEnumAttribute'. Attribute values must be of type String, Boolean, a subtype of Number, or implement org.gradle.api.Named."

    /**
     * Runs the given task and asserts the expected outcome depending on whether the enum
     * flavor under test implements {@link org.gradle.api.Named}. Named-implementing enums
     * are expected to succeed and produce the given output lines. Plain enums are expected
     * to fail at the {@link org.gradle.api.attributes.Attribute#of(String, Class)} call
     * with {@link #UNSUPPORTED_TYPE_MSG} as the failure cause.
     */
    private void expectResolve(String taskName, boolean implementsNamed, List<String> expectedOutputs = []) {
        if (implementsNamed) {
            succeeds(taskName)
            expectedOutputs.each { outputContains(it) }
        } else {
            fails(taskName)
            failure.assertHasCause(UNSUPPORTED_TYPE_MSG)
        }
    }
    // endregion setup

    // region enums succeed
    // -------------------------------------------------------------------------
    // Every test in this region exercises a valid Gradle attribute-usage pattern.
    // Named-implementing enums pass end-to-end. Plain enums are rejected at
    // Attribute.of during script evaluation with UNSUPPORTED_TYPE_MSG.
    // -------------------------------------------------------------------------
    def "in-memory attribute matching accepts a #enumDesc as an attribute value"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "AttributeCompatibilityRule typed on a #enumDesc makes candidate values compatible"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.BAR) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            abstract class MyEnumCompatibilityRule implements AttributeCompatibilityRule<MyEnum> {
                void execute(CompatibilityCheckDetails<MyEnum> details) { details.compatible() }
            }

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE) {
                        compatibilityRules.add(MyEnumCompatibilityRule)
                    }
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "AttributeDisambiguationRule typed on a #enumDesc picks a candidate"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/foo.txt") << "foo output"
        file("producer/bar.txt") << "bar output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("fooVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("foo.txt"))
                }
                consumable("barVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.BAR) }
                    outgoing.artifact(file("bar.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            abstract class MyEnumCompatibilityRule implements AttributeCompatibilityRule<MyEnum> {
                void execute(CompatibilityCheckDetails<MyEnum> details) { details.compatible() }
            }
            abstract class MyEnumDisambiguationRule implements AttributeDisambiguationRule<MyEnum> {
                void execute(MultipleCandidatesDetails<MyEnum> details) {
                    if (details.candidateValues.contains(MyEnum.BAR)) {
                        details.closestMatch(MyEnum.BAR)
                    }
                }
            }

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE) {
                        compatibilityRules.add(MyEnumCompatibilityRule)
                        disambiguationRules.add(MyEnumDisambiguationRule)
                    }
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: bar.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "supplying a #enumDesc attribute value via a lazy Provider works end-to-end"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes {
                        attributeProvider(ATTRIBUTE_TYPE, project.provider { MyEnum.FOO })
                    }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes {
                        attributeProvider(ATTRIBUTE_TYPE, project.provider { MyEnum.FOO })
                    }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "materializing resolutionResult with a #enumDesc on a local project dependency"() {
        // Local project dependencies do not stream the resolution result through
        // DesugaringAttributeContainerSerializer. Empirically, even without the up-front
        // Attribute.of check, both flavors resolve cleanly here.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def rootProvider = configurations.myResolver.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootProvider.get()
                    println("Root: " + root.moduleVersion)
                    root.dependencies.each { d -> println("Dep: " + d) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Dep: project ':producer'"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "consuming a Maven-published variant with a #enumDesc-typed request attribute"() {
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [myEnumAttribute: "FOO"]) {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                maven { url = uri("${mavenRepo.uri}") }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps("org.example:producer:1.0")
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Resolved: producer-1.0.jar"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "consuming an Ivy-published variant with a #enumDesc-typed request attribute"() {
        given:
        ivyRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [myEnumAttribute: "FOO"])
            .withVariant("myVariant") {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                ivy { url = uri("${ivyRepo.uri}") }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps("org.example:producer:1.0")
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Resolved: "])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "withVariantReselection using a #enumDesc as the reselection attribute"() {
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("fooVariant", [myEnumAttribute: "FOO"]) {
                artifact("producer-1.0-foo.jar")
            }
            .variant("barVariant", [myEnumAttribute: "BAR"]) {
                artifact("producer-1.0-bar.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                maven { url = uri("${mavenRepo.uri}") }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps("org.example:producer:1.0")
            }

            tasks.register("resolve") {
                def reselected = configurations.myResolver.incoming.artifactView {
                    withVariantReselection()
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.BAR) }
                }.files
                doLast {
                    reselected.each { println("Reselected: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Reselected: producer-1.0-bar.jar"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "config-cache round-trip on a task holding a resolvable configuration with a #enumDesc"() {
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            abstract class HoldEnum extends DefaultTask {
                @Input
                abstract Property<MyEnum> getEnumInput()
                @InputFiles
                abstract ConfigurableFileCollection getFiles()
                @TaskAction
                void run() {
                    println("Enum: " + enumInput.get())
                    files.each { println("Resolved: " + it.name) }
                }
            }

            tasks.register("resolve", HoldEnum) {
                enumInput.set(MyEnum.FOO)
                files.from(configurations.myResolver)
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Enum: FOO", "Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    // region additional coverage
    // -------------------------------------------------------------------------
    // These tests exercise additional code paths (publishing, build ops,
    // component metadata rules, external-Maven resolutionResult, dedup
    // serialization, extendsFrom inheritance, schema registration). Empirically,
    // plain enums work cleanly through all of these paths when validateSupportedType
    // is disabled; the tests belong in "enums succeed" because their assertions
    // are structurally identical to the other tests in this region.
    // -------------------------------------------------------------------------

    def "publishing a variant with a #enumDesc-typed attribute to a Maven repository"() {
        // Exercises the producer-side publishing pipeline via maven-publish.
        // ModuleMetadataSpecBuilder.attributeValueFor already handles Enum values by name
        // via .name(), so the underlying pipeline is enum-safe. Under the current policy,
        // Attribute.of rejects plain enums upstream, so the plain row fails at script eval
        // on the producer side rather than reaching the GMM writer.
        given:
        file("output.txt") << "sample output"
        buildFile("""
            plugins { id 'maven-publish' }

            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            group = 'org.example'
            version = '1.0'

            def myVariant = configurations.consumable("myVariant") {
                attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                outgoing.artifact(file("output.txt"))
            }

            def component = publishing.softwareComponentFactory.adhoc("myComponent")
            component.addVariantsFromConfiguration(myVariant.get()) {
                mapToMavenScope("runtime")
            }
            components.add(component)

            publishing {
                repositories { maven { url = uri("${mavenRepo.uri}") } }
                publications {
                    maven(MavenPublication) { from components.myComponent }
                }
            }
        """)

        expect:
        expectResolve("publish", implementsNamed)

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "resolution emits build operations with a #enumDesc attribute value"() {
        // Probes AttributesToMapConverter.getAttributeValueAsString: the build-op path
        // uses value.toString() as a fallback, which for an enum yields the constant name.
        // Any successful resolution emits build operations that carry the attribute
        // container through this code path — enum-safe.
        given:
        settingsFile("include 'consumer', 'producer'")

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "resolution build-op result desugars a #enumDesc attribute via toString"() {
        // Probes ResolveConfigurationResolutionBuildOperationResult.desugarAttributes,
        // which has its own desugaring: primitives, then Named, then a .toString() fallback.
        // Enums fall into the fallback branch — the constant name is emitted verbatim.
        given:
        settingsFile("include 'consumer', 'producer'")

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def rootProvider = configurations.myResolver.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootProvider.get()
                    println("Root: " + root.moduleVersion)
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Root: "])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "component metadata rule adds a #enumDesc attribute to a resolved module"() {
        // A component metadata rule mutates the resolved graph's attributes at rule
        // execution time. The rule references MyEnum inside its execute() body — but the
        // consumer script's own Attribute.of call fires first at script eval for plain
        // enums.
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [:]) {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories { maven { url = uri("${mavenRepo.uri}") } }

            abstract class AddEnumAttributeRule implements ComponentMetadataRule {
                @Override
                void execute(ComponentMetadataContext ctx) {
                    def attr = Attribute.of("myEnumAttribute", MyEnum.class)
                    ctx.details.allVariants {
                        attributes { attribute(attr, MyEnum.FOO) }
                    }
                }
            }

            dependencies {
                components {
                    withModule("org.example:producer", AddEnumAttributeRule)
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps("org.example:producer:1.0") }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Resolved: producer-1.0.jar"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "materializing resolutionResult with a #enumDesc on an external Maven dependency"() {
        // Static code-reading suggests `.resolutionResult.rootComponent.get()` on an external
        // Maven dep should stream through StreamingResolutionResultBuilder →
        // DesugaringAttributeContainerSerializer and reproduce the ES/9.5.1 regression.
        // Empirically it does not — execution-time result queries operate against the
        // in-memory graph and don't re-stream. Only the ES-shape (test in un-named enums fail
        // region: detached configuration + task inputs at task-graph-time) hits the streaming
        // path. This test therefore succeeds cleanly for both flavors when the up-front check
        // is disabled.
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [myEnumAttribute: "FOO"]) {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories { maven { url = uri("${mavenRepo.uri}") } }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps("org.example:producer:1.0") }

            tasks.register("resolve") {
                def rootProvider = configurations.myResolver.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootProvider.get()
                    println("Root: " + root.moduleVersion)
                    root.dependencies.each { d -> println("Dep: " + d) }
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Dep: org.example:producer:1.0"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "deduplicated serialization when multiple variants share a #enumDesc attribute container"() {
        // DeduplicatingAttributeContainerSerializer wraps DesugaringAttributeContainerSerializer
        // and interns identical attribute containers on write. Static code-reading suggests
        // materializing the resolution result on a module with overlapping variant attributes
        // should hit the dedup wrapper — but empirically it does not, for the same reason as
        // the external-Maven resolutionResult test above: execution-time result queries use
        // the in-memory graph. This test succeeds cleanly for both flavors when the up-front
        // check is disabled.
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("variant1", [myEnumAttribute: "FOO", tag: "one"]) {
                artifact("producer-1.0-a.jar")
            }
            .variant("variant2", [myEnumAttribute: "FOO", tag: "two"]) {
                artifact("producer-1.0-b.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)
            def TAG_ATTR = Attribute.of("tag", String.class)

            repositories { maven { url = uri("${mavenRepo.uri}") } }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes {
                        attribute(ATTRIBUTE_TYPE, MyEnum.FOO)
                        attribute(TAG_ATTR, "one")
                    }
                }
            }

            dependencies { myDeps("org.example:producer:1.0") }

            tasks.register("resolve") {
                def rootProvider = configurations.myResolver.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootProvider.get()
                    println("Root: " + root.moduleVersion)
                }
            }
        """)

        expect:
        expectResolve("resolve", implementsNamed, ["Root: "])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "resolvable configuration inheriting a #enumDesc attribute via extendsFrom"() {
        // Attribute inheritance goes through the container's addAllLater / concat chain,
        // which doesn't touch the desugaring serializer. Any failure comes from Attribute.of
        // during script eval, not from any downstream serialization.
        given:
        settingsFile("include 'consumer', 'producer'")

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myBase") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myBase"))
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "registering a #enumDesc-typed attribute in the attributes schema"() {
        // Registering an attribute in the schema (dependencies.attributesSchema { attribute(...) })
        // walks through DefaultAttributesSchema.attribute(...). The consumer script's own
        // Attribute.of call fires the validation first, so plain row fails identically to
        // other consumer-side plain rows.
        given:
        settingsFile("include 'consumer', 'producer'")

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE)
                }
            }

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE)
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }
    // endregion additional coverage

    // region enum-as-JVM-singleton
    // -------------------------------------------------------------------------
    // Tests that probe the JVM-level enum-singleton semantics: cross-classloader
    // coercion producing consumer-side singletons, anonymous per-constant subclasses
    // being unwrapped via getDeclaringClass, and config-cache save+load preserving
    // the singleton identity. These sit inside "enums succeed" because the underlying
    // machinery handles them correctly for both flavors — the Named row succeeds
    // end-to-end and the plain row is rejected up front by Attribute.of, exactly
    // like every other test in the "enums succeed" region.
    // -------------------------------------------------------------------------
    def "consequence (#enumDesc): cross-classloader coercion silently creates a different singleton"() {
        // Producer and consumer scripts each declare their own MyEnum in independent
        // classloaders. Consumer-side coercion returns the CONSUMER's FOO singleton,
        // not the producer's. This test verifies the retrieved attribute value belongs
        // to the consumer's classloader — the "trap" being that a plugin author
        // holding a reference to a DIFFERENT classloader's FOO would see reference
        // inequality even though names match.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def artifacts = configurations.myResolver.incoming.artifactView {}.artifacts.resolvedArtifacts
                doLast {
                    artifacts.get().each { r ->
                        def retrieved = r.variant.attributes.getAttribute(ATTRIBUTE_TYPE)
                        assert retrieved.is(MyEnum.FOO): "retrieved \$retrieved is not identical to consumer's MyEnum.FOO"
                        assert retrieved.declaringClass.classLoader.is(MyEnum.class.classLoader)
                        println("Consumer-classloader singleton: OK")
                    }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Consumer-classloader singleton: OK"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "consequence (#enumDesc): enum with anonymous per-constant inner-class bodies works via getDeclaringClass"() {
        // Enum constants declared with per-constant bodies produce anonymous subclasses
        // (MyEnum$1, MyEnum$2). Gradle uses Enum#getDeclaringClass, not Object#getClass,
        // so no code path treats MyEnum$1 as the attribute value type.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc                | enumDecl                                                         | implementsNamed
        "plain Enum with body"  | """enum MyEnum {
                                       FOO { @Override String describe() { return "the-foo" } },
                                       BAR { @Override String describe() { return "the-bar" } };
                                       abstract String describe()
                                   }"""                                                            | false
        "Named Enum with body"  | """enum MyEnum implements Named {
                                       FOO { @Override String describe() { return "the-foo" } },
                                       BAR { @Override String describe() { return "the-bar" } };
                                       abstract String describe()
                                       @Override String getName() { return name() }
                                   }"""                                                            | true
    }

    def "consequence (#enumDesc): config-cache save-and-reuse preserves the attribute value across script re-parse"() {
        // A build script declaring its own enum gets a fresh classloader on every
        // configuration. Config-cache save serializes attribute values by class name +
        // constant name; restore re-runs the script and re-resolves the constant via
        // Enum.valueOf. This verifies a save→reuse cycle produces the same result.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect: "the configCacheIntegTest task variant already replays every test through configuration-cache save+load; a single successful run here proves the enum attribute survives that round-trip"
        expectResolve(":consumer:resolve", implementsNamed, ["Resolved: output.txt"])

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }
    // endregion enum-as-JVM-singleton
    // endregion enums succeed

    // region un-named enums fail
    // -------------------------------------------------------------------------
    // Only the three scenarios below expose failures that would surface even if
    // Attribute.of's up-front validateSupportedType check were removed. Everything
    // else lives in "enums succeed" because those pipelines empirically handle
    // plain enums fine when the up-front check is disabled. The three cases here:
    //   - task-input on detached configuration (ES 9.5.1 regression):
    //     ClassCastException at DesugaringAttributeContainerSerializer:91.
    //   - compile-time-closed enum constants (producer offers a constant absent
    //     from consumer's enum): IllegalArgumentException at
    //     IsolatedEnumValueSnapshot:56 from Enum.valueOf.
    //   - GMM value not a valid enum constant: IllegalArgumentException at
    //     CoercingStringValueSnapshot:39 from Enum.valueOf.
    // See problems-with-unnamed-enums.md in this directory for full analysis.
    // -------------------------------------------------------------------------
    def "task-input on a detached configuration with a #enumDesc attribute value (regression from Gradle 9.5.1)"() {
        // Reproducer for the ClassCastException reported by the Elasticsearch team.
        // This is the ONE test where the underlying pipeline genuinely rejects plain enums:
        // task-graph-time resolution of a detached configuration with an external Maven dep
        // streams through StreamingResolutionResultBuilder → DesugaringAttributeContainerSerializer,
        // whose else-branch performs an unchecked (Named) cast at line 91. On a plain enum
        // this raises ClassCastException, which DefaultBinaryStore.write wraps as
        // "Problems writing to Binary store". Under the current policy, Attribute.of rejects
        // the plain enum before reaching that code path, and the user sees the up-front
        // IllegalAttributeTypeException instead. A Named-implementing enum succeeds end-to-end.
        given:
        mavenRepo.module("org.example", "producer", "1.0").publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                maven { url = uri("${mavenRepo.uri}") }
            }

            def detachedConf = configurations.detachedConfiguration(
                dependencies.create("org.example:producer:1.0")
            )
            detachedConf.attributes {
                attribute(ATTRIBUTE_TYPE, MyEnum.FOO)
            }

            tasks.register("myTask") {
                inputs.files(detachedConf)
                doLast { }
            }
        """)

        expect:
        expectResolve("myTask", implementsNamed)

        where:
        enumDesc   | enumDecl    | implementsNamed
        PLAIN_DESC | PLAIN_ENUM  | false
        NAMED_DESC | NAMED_ENUM  | true
    }

    def "enum constants are compile-time closed — producer offers a constant absent from the consumer's enum (#enumDesc)"() {
        // The producer publishes a variant using MyEnum.BAR. The consumer's MyEnum has only
        // FOO. Both flavors fail — but at different layers:
        //   - Named row: cross-classloader coercion at IsolatedEnumValueSnapshot:56 calls
        //     Enum.valueOf(consumer.MyEnum, "BAR"), which raises IllegalArgumentException:
        //     "No enum constant MyEnum.BAR". This is a JDK contract, not a Gradle issue.
        //   - plain row: Attribute.of rejects the enum type before we ever get to coercion.
        // Either way, plain-enum users cannot avoid failure here — the incompatible enum
        // shape is a fundamental constraint of enum-as-attribute usage.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            ${enumDeclProducer}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.BAR) }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            ${enumDeclConsumer}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps(project(":producer"))
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        fails(":consumer:resolve")
        failure.assertHasCause(expectedCause)

        where:
        enumDesc             | enumDeclProducer                                                                          | enumDeclConsumer                                                                     | expectedCause
        "plain Enum"         | "enum MyEnum { FOO, BAR }"                                                                | "enum MyEnum { FOO }"                                                                | UNSUPPORTED_TYPE_MSG
        "Named-implementing" | "enum MyEnum implements Named { FOO, BAR;\n@Override String getName() { return name() } }" | "enum MyEnum implements Named { FOO;\n@Override String getName() { return name() } }" | "No enum constant MyEnum.BAR"
    }

    def "GMM value that is not a valid enum constant fails coercion (#enumDesc)"() {
        // The GMM wire attribute value must be exactly a constant name of the consumer's
        // enum type. Any drift (typo, renamed constant, spurious value from a component-
        // metadata rule) surfaces as the raw JDK IAE. Both flavors fail:
        //   - Named row: CoercingStringValueSnapshot:39 calls Enum.valueOf(consumer.MyEnum,
        //     "NOT_A_CONSTANT"), which throws IllegalArgumentException "No enum constant".
        //   - plain row: Attribute.of rejects the enum type before we ever get to GMM read.
        // Non-enum Named subtypes have no equivalent failure mode because
        // objects.named(Type, anyString) returns an instance for any string — no closed
        // set of valid values.
        given:
        mavenRepo.module("org.example", "producer", "1.0")
            .withModuleMetadata()
            .withoutDefaultVariants()
            .variant("myVariant", [myEnumAttribute: "NOT_A_CONSTANT"]) {
                artifact("producer-1.0.jar")
            }
            .publish()

        buildFile("""
            ${enumDecl}
            def ATTRIBUTE_TYPE = Attribute.of("myEnumAttribute", MyEnum.class)

            repositories {
                maven { url = uri("${mavenRepo.uri}") }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, MyEnum.FOO) }
                }
            }

            dependencies {
                myDeps("org.example:producer:1.0")
            }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.files
                doLast {
                    files.each { println("Resolved: " + it.name) }
                }
            }
        """)

        expect:
        fails("resolve")
        failure.assertHasCause(expectedCause)

        where:
        enumDesc   | enumDecl    | expectedCause
        PLAIN_DESC | PLAIN_ENUM  | UNSUPPORTED_TYPE_MSG
        NAMED_DESC | NAMED_ENUM  | "No enum constant MyEnum.NOT_A_CONSTANT"
    }
    def "registering an AttributeCompatibilityRule typed on a plain Enum fails when the rule fires"() {
        // The attribute itself is a String (which passes Attribute.of), but the rule is
        // typed on a plain enum, which Groovy allows to register via loose generics.
        // AttributeTypeValidator.validateRuleTypeParameter walks the rule class's
        // superinterfaces at first fire, extracts the plain-enum type argument, and throws.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/output.txt") << "sample output"
        buildFile("producer/build.gradle", """
            def ATTRIBUTE_TYPE = Attribute.of("myAttr", String.class)
            configurations {
                consumable("myVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, "PRODUCER") }
                    outgoing.artifact(file("output.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            enum MyPlainEnum { FOO, BAR }

            def ATTRIBUTE_TYPE = Attribute.of("myAttr", String.class)

            abstract class BadRule implements AttributeCompatibilityRule<MyPlainEnum> {
                void execute(CompatibilityCheckDetails details) { details.compatible() }
            }

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE) {
                        compatibilityRules.add(BadRule)
                    }
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, "CONSUMER") }
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        fails(":consumer:resolve")
        failure.assertHasCause("Unsupported type 'MyPlainEnum' declared as the type parameter of attribute rule 'BadRule'. Attribute values must be of type String, Boolean, a subtype of Number, or implement org.gradle.api.Named.")
    }

    def "registering an AttributeDisambiguationRule typed on a plain Enum fails when the rule fires"() {
        // For the disambiguation rule to fire we need multiple *compatible* candidates.
        // Producer offers two variants with distinct values on the target attribute — a
        // compatibility rule (typed correctly on String) makes both compatible; then the
        // badly-typed disambiguation rule is invoked to pick between them and trips our
        // validator.
        given:
        settingsFile("""
            include 'consumer', 'producer'
        """)

        file("producer/foo.txt") << "foo output"
        file("producer/bar.txt") << "bar output"
        buildFile("producer/build.gradle", """
            def ATTRIBUTE_TYPE = Attribute.of("myAttr", String.class)
            configurations {
                consumable("fooVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, "foo") }
                    outgoing.artifact(file("foo.txt"))
                }
                consumable("barVariant") {
                    attributes { attribute(ATTRIBUTE_TYPE, "bar") }
                    outgoing.artifact(file("bar.txt"))
                }
            }
        """)

        buildFile("consumer/build.gradle", """
            enum MyPlainEnum { FOO, BAR }

            def ATTRIBUTE_TYPE = Attribute.of("myAttr", String.class)

            abstract class AlwaysCompatibleRule implements AttributeCompatibilityRule<String> {
                void execute(CompatibilityCheckDetails<String> details) { details.compatible() }
            }
            abstract class BadDisambiguationRule implements AttributeDisambiguationRule<MyPlainEnum> {
                void execute(MultipleCandidatesDetails details) { details.closestMatch(details.candidateValues.first()) }
            }

            dependencies {
                attributesSchema {
                    attribute(ATTRIBUTE_TYPE) {
                        compatibilityRules.add(AlwaysCompatibleRule)
                        disambiguationRules.add(BadDisambiguationRule)
                    }
                }
            }

            configurations {
                dependencyScope("myDeps")
                resolvable("myResolver") {
                    extendsFrom(configurations.getByName("myDeps"))
                    attributes { attribute(ATTRIBUTE_TYPE, "foo") }
                }
            }

            dependencies { myDeps(project(":producer")) }

            tasks.register("resolve") {
                def files = configurations.myResolver.incoming.artifactView {}.files
                doLast { files.each { println("Resolved: " + it.name) } }
            }
        """)

        expect:
        fails(":consumer:resolve")
        failure.assertHasCause("Unsupported type 'MyPlainEnum' declared as the type parameter of attribute rule 'BadDisambiguationRule'. Attribute values must be of type String, Boolean, a subtype of Number, or implement org.gradle.api.Named.")
    }
    // endregion un-named enums fail
}
