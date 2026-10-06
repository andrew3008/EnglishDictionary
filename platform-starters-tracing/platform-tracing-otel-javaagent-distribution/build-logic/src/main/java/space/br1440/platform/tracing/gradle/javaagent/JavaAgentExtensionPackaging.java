package space.br1440.platform.tracing.gradle.javaagent;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;

final class JavaAgentExtensionPackaging {

    static final String MAVEN_ARTIFACT_ID = "platform-tracing-otel-javaagent-extension";

    private static final String BUILD_GROUP = "build";
    private static final String SHADED_PREFIX = "space.br1440.platform.tracing.otel.javaagent.shaded.";
    private static final List<String> INVALIDATED_SIGNATURES = List.of(
            "META-INF/*.SF",
            "META-INF/*.RSA",
            "META-INF/*.DSA",
            "META-INF/*.EC"
    );
    private static final List<String> MODULE_DESCRIPTORS = List.of(
            "module-info.class",
            "META-INF/versions/*/module-info.class"
    );
    private static final String LOG_EVENT_SCHEMA_RESOURCE = "platform-log-event.schema.json";

    private JavaAgentExtensionPackaging() {
    }

    static TaskProvider<ShadowJar> configure(Project project,
                                             Configuration extensionRuntime,
                                             Provider<File> extensionLibrary) {
        Provider<JavaAgentDistribution.ExtensionSchema> extensionSchema = extensionLibrary.map(
                library -> JavaAgentDistribution.ExtensionSchema.readFrom(library.toPath()));

        return project.getTasks().named("shadowJar", ShadowJar.class, task -> {
            task.setGroup(BUILD_GROUP);
            task.getArchiveFileName().set(MAVEN_ARTIFACT_ID + "-" + project.getVersion() + ".jar");

            // Shadow по умолчанию назначает classifier all.
            task.getArchiveClassifier().set("");

            task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("libs"));
            task.getConfigurations().set(Set.of(extensionRuntime));
            task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
            task.setPreserveFileTimestamps(false);
            task.setReproducibleFileOrder(true);

            configureContentExclusions(task);
            configureRelocations(task);
            configureManifest(task, project, extensionSchema);
        });
    }

    private static void configureContentExclusions(ShadowJar task) {
        // Подписи исходных JAR становятся недействительными после объединения содержимого.
        INVALIDATED_SIGNATURES.forEach(task::exclude);

        // Дескрипторы отдельных модулей не описывают объединённый JAR.
        MODULE_DESCRIPTORS.forEach(task::exclude);

        // Схема не является runtime-ресурсом; её версия и SHA-256 сохраняются в manifest.
        task.exclude(LOG_EVENT_SCHEMA_RESOURCE);
    }

    private static void configureRelocations(ShadowJar task) {
        task.relocate("com.google.protobuf", SHADED_PREFIX + "com.google.protobuf");
        task.relocate("io.opentelemetry.proto", SHADED_PREFIX + "io.opentelemetry.proto");
    }

    private static void configureManifest(ShadowJar task,
                                          Project project,
                                          Provider<JavaAgentDistribution.ExtensionSchema> extensionSchema) {
        // Порядок атрибутов входит в воспроизводимые байты JAR и его SHA-256.
        Map<String, Object> manifestAttributes = new LinkedHashMap<>();
        manifestAttributes.put("Implementation-Title", MAVEN_ARTIFACT_ID);
        manifestAttributes.put("Implementation-Version", project.getVersion());
        manifestAttributes.put(
                "Platform-Log-Schema-Version",
                extensionSchema.map(JavaAgentDistribution.ExtensionSchema::semanticVersion)
        );
        manifestAttributes.put(
                "Platform-Log-Schema-SHA256",
                extensionSchema.map(JavaAgentDistribution.ExtensionSchema::sha256)
        );
        task.getManifest().attributes(manifestAttributes);
    }
}
