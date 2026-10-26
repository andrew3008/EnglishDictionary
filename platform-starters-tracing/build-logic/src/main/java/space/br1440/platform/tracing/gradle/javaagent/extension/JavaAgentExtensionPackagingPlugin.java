package space.br1440.platform.tracing.gradle.javaagent.extension;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.AbstractCopyTask;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.WriteProperties;

import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;

import space.br1440.platform.tracing.gradle.javaagent.ComponentIdentity;
import space.br1440.platform.tracing.gradle.javaagent.PackagedExtensionAttributes;
import space.br1440.platform.tracing.gradle.javaagent.VersionCatalogs;

@SuppressWarnings("unused")
public final class JavaAgentExtensionPackagingPlugin implements Plugin<Project> {

    private static final String EXTENSION_ARTIFACT_ID = "platform-tracing-otel-javaagent-extension";
    private static final String PACKAGED_ELEMENTS_NAME = "packagedJavaAgentExtensionElements";
    private static final List<String> PROVIDED_MODULES = List.of(
            "io.opentelemetry:opentelemetry-api",
            "io.opentelemetry:opentelemetry-context",
            "io.opentelemetry:opentelemetry-common"
    );
    private static final String EXTENSION_SCHEMA_PATH =
            "src/main/resources/META-INF/platform-tracing/agent-logging-schema.properties";
    private static final String EXTENSION_ARTIFACT_METADATA_PATH =
            "space/br1440/platform/tracing/otel/javaagent/readiness/platform-extension-build.properties";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply(ShadowPlugin.class);

        Provider<String> version = project.provider(() -> project.getVersion().toString());
        Provider<String> otelVersion = VersionCatalogs.requiredVersion(project, "bom.opentelemetry");
        Provider<String> otelProtoVersion = VersionCatalogs.requiredVersion(project, "bom.otelproto");
        Provider<String> protobufVersion = VersionCatalogs.requiredVersion(project, "bom.protobuf");
        TaskProvider<ValidateJavaAgentExtensionComponentsTask> validateComponents = registerValidation(
                project,
                otelVersion,
                otelProtoVersion,
                protobufVersion
        );
        RegularFile schemaFile = project.getLayout().getProjectDirectory().file(EXTENSION_SCHEMA_PATH);

        registerBuildMetadata(project, version);

        TaskProvider<ShadowJar> packageExtension = configureShadowJar(project, version, validateComponents, schemaFile);
        registerPackagedVariant(project, packageExtension);
        forbidPublishing(project);
    }

    private static TaskProvider<ValidateJavaAgentExtensionComponentsTask> registerValidation(Project project,
                                                                                             Provider<String> otelVersion,
                                                                                             Provider<String> otelProtoVersion,
                                                                                             Provider<String> protobufVersion) {
        Provider<Set<String>> packagedComponents = otelProtoVersion.zip(
                protobufVersion,
                (otelProto, protobuf) -> Set.of(
                        ComponentIdentity.project(
                                ":platform-starters-tracing:spring-boot-starter-platform-tracing-api"
                        ).value(),

                        ComponentIdentity.project(
                                ":platform-starters-tracing:platform-tracing-runtime-policy"
                        ).value(),

                        ComponentIdentity.project(
                                ":platform-starters-common:platform-starters-common-util"
                        ).value(),

                        ComponentIdentity.module(
                                "io.opentelemetry.proto",
                                "opentelemetry-proto",
                                otelProto
                        ).value(),

                        ComponentIdentity.module(
                                "com.google.protobuf",
                                "protobuf-java",
                                protobuf
                        ).value()
                )
        );

        Set<String> followedModules = Set.of(ComponentIdentity.parseModule("jakarta.annotation:jakarta.annotation-api"));
        Provider<Set<String>> providedComponents = otelVersion.map(requiredVersion -> {
            Set<String> components = new LinkedHashSet<>();
            PROVIDED_MODULES.forEach(module ->
                    components.add(ComponentIdentity.parse(module + ":" + requiredVersion).value())
            );
            return components;
        });

        Provider<Configuration> runtimeClasspath = project.getConfigurations()
                .named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);

        Provider<Set<String>> resolvedComponents = runtimeClasspath.flatMap(configuration ->
                configuration.getIncoming().getArtifacts().getResolvedArtifacts()
                        .map(artifacts -> {
                            Set<String> identities = new LinkedHashSet<>();
                            for (ResolvedArtifactResult artifact : artifacts) {
                                identities.add(ComponentIdentity.of(artifact.getId().getComponentIdentifier()).value());
                            }
                            return identities;
                        }));

        TaskProvider<ValidateJavaAgentExtensionComponentsTask> validateComponents = project.getTasks().register(
                "validateJavaAgentExtensionComponents",
                ValidateJavaAgentExtensionComponentsTask.class,
                task -> {
                    task.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                    task.setDescription("Validates the complete runtime component set before javaagent extension packaging");
                    task.getResolvedComponents().set(resolvedComponents);
                    task.getPackagedComponents().set(packagedComponents);
                    task.getFollowedModules().set(followedModules);
                    task.getProvidedComponents().set(providedComponents);
                }
        );
        project.getTasks().named(JavaBasePlugin.CHECK_TASK_NAME).configure(task -> task.dependsOn(validateComponents));
        return validateComponents;
    }

    private static void registerBuildMetadata(Project project, Provider<String> version) {
        Provider<Directory> metadataOutputDir = project
                .getLayout()
                .getBuildDirectory()
                .dir("generated/extension-artifact-metadata");

        TaskProvider<WriteProperties> writeExtensionArtifactMetadata = project.getTasks().register(
                "writeExtensionArtifactMetadata",
                WriteProperties.class,
                task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);
                    task.setDescription("Writes classloader-local identity and version metadata for the javaagent extension");
                    task.getDestinationFile().set(
                            metadataOutputDir
                                    .map(directory -> directory
                                            .file(EXTENSION_ARTIFACT_METADATA_PATH)
                                    ));
                    task.property("format.version", "1");
                    task.property("artifact", EXTENSION_ARTIFACT_ID);
                    task.property("version", version);
                }
        );

        project.getTasks()
                .named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME, AbstractCopyTask.class)
                .configure(task -> task
                        .from(project
                                .files(metadataOutputDir)
                                .builtBy(writeExtensionArtifactMetadata)
                        )
                );
    }

    private static TaskProvider<ShadowJar> configureShadowJar(Project project,
                                                              Provider<String> version,
                                                              TaskProvider<ValidateJavaAgentExtensionComponentsTask> validateComponents,
                                                              RegularFile schemaFile) {
        Provider<LogSchemaIdentity> logSchemaIdentity = project.getProviders()
                .fileContents(schemaFile)
                .getAsText()
                .map(content -> LogSchemaIdentity.parse(
                        content,
                        schemaFile.getAsFile().getAbsolutePath()
                ));

        Map<String, String> relocationRules = new LinkedHashMap<>();
        relocationRules.put(
                "com.google.protobuf",
                "space.br1440.platform.tracing.otel.javaagent.shaded.com.google.protobuf"
        );
        relocationRules.put(
                "io.opentelemetry.proto",
                "space.br1440.platform.tracing.otel.javaagent.shaded.io.opentelemetry.proto"
        );

        return project.getTasks().named(ShadowJar.SHADOW_JAR_TASK_NAME, ShadowJar.class, shadow -> {
            shadow.setGroup(BasePlugin.BUILD_GROUP);
            shadow.setDescription("Packages the production javaagent extension and its approved runtime dependencies");
            shadow.dependsOn(validateComponents);
            shadow.getArchiveBaseName().set(EXTENSION_ARTIFACT_ID);
            shadow.getArchiveClassifier().set("");
            shadow.getDestinationDirectory().set(
                    project.getLayout().getBuildDirectory().dir("packaged-javaagent-extension")
            );
            shadow.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
            shadow.getFailOnDuplicateEntries().set(true);
            shadow.setPreserveFileTimestamps(false);
            shadow.setReproducibleFileOrder(true);
            shadow.getInputs().file(schemaFile).withPropertyName("logSchemaDescriptor");

            shadow.exclude("META-INF/*.SF");
            shadow.exclude("META-INF/*.RSA");
            shadow.exclude("META-INF/*.DSA");
            shadow.exclude("META-INF/*.EC");

            shadow.exclude("module-info.class");
            shadow.exclude("META-INF/versions/*/module-info.class");

            shadow.exclude("platform-log-event.schema.json");

            shadow.dependencies(filter ->
                    PROVIDED_MODULES.forEach(module -> filter.exclude(filter.dependency(module + ":.*"))));

            shadow.getInputs().property("relocationRules", relocationRules);
            relocationRules.forEach(shadow::relocate);

            shadow.manifest(manifest -> {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("Implementation-Title", EXTENSION_ARTIFACT_ID);
                attributes.put("Implementation-Version", version);

                attributes.put(
                        "Platform-Log-Schema-Version",
                        logSchemaIdentity.map(identity -> Integer.toString(identity.version()))
                );

                attributes.put(
                        "Platform-Log-Schema-SHA256",
                        logSchemaIdentity.map(LogSchemaIdentity::sha256)
                );

                manifest.attributes(attributes);
            });
        });
    }

    private static void registerPackagedVariant(Project project,
                                                TaskProvider<ShadowJar> packageExtension) {
        Configuration packagedElements = project.getConfigurations().create(PACKAGED_ELEMENTS_NAME, configuration -> {
            configuration.setCanBeConsumed(true);
            configuration.setCanBeResolved(false);
            PackagedExtensionAttributes.configure(project.getObjects(), configuration.getAttributes());
        });

        project.getArtifacts().add(
                packagedElements.getName(),
                packageExtension.flatMap(ShadowJar::getArchiveFile),
                artifact -> {
                    artifact.builtBy(packageExtension);
                    artifact.setType(ArtifactTypeDefinition.JAR_TYPE);
                }
        );
    }

    private static void forbidPublishing(Project project) {
        project.getPlugins().withId("maven-publish", ignored -> {
            throw new GradleException("""
                    %s publishes unshaded javaagent extension classes; \
                    publish the javaagent distribution instead\
                    """.formatted(project.getPath()));
        });
    }

}
