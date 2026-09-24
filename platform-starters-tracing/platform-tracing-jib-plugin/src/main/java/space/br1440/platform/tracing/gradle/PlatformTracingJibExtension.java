package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.gradle.api.Project;

import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FileEntry;
import com.google.cloud.tools.jib.api.buildplan.LayerObject;
import com.google.cloud.tools.jib.gradle.ExtensionParameters;
import com.google.cloud.tools.jib.gradle.JibExtension;
import com.google.cloud.tools.jib.gradle.extension.GradleData;
import com.google.cloud.tools.jib.gradle.extension.JibGradlePluginExtension;
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger;
import com.google.cloud.tools.jib.plugins.extension.JibPluginExtensionException;

public final class PlatformTracingJibExtension implements JibGradlePluginExtension<Void> {

    static final String EXPECTED_SHA256_PROPERTY = "expectedSha256";
    static final String IMAGE_AGENT_DIRECTORY = LaunchContractPolicy.IMAGE_AGENT_DIRECTORY;

    private static final String AGENT_LAYER_NAME = "platform-tracing-agent";
    private static final String CURRENT_DIRECTORY = ".";
    private static final String PARENT_DIRECTORY = "..";
    private static final String WHITEOUT_PREFIX = ".wh.";
    private static final String OPT_DIRECTORY = "/opt";

    @Override
    public Optional<Class<Void>> getExtraConfigType() {
        return Optional.empty();
    }

    @Override
    public ContainerBuildPlan extendContainerBuildPlan(ContainerBuildPlan buildPlan,
                                                       Map<String, String> properties,
                                                       Optional<Void> extraConfig,
                                                       GradleData gradleData,
                                                       ExtensionLogger logger) throws JibPluginExtensionException {
        requireSingleRegistration(gradleData.getProject());

        VerifyControlledAgentTask verify = gradleData.getProject().getTasks()
                .named(PlatformTracingJibPlugin.TASK_VERIFY_CONTROLLED_AGENT, VerifyControlledAgentTask.class)
                .get();

        Path agentJar = verify.getAgentDirectory().file(verify.getAgentFileName()).get().getAsFile().toPath();
        String expectedSha256 = requireProperty(properties, EXPECTED_SHA256_PROPERTY);

        List<String> violations = new ArrayList<>(
                LaunchContractPolicy.violations(buildPlan.getEntrypoint(), buildPlan.getEnvironment())
        );
        violations.addAll(layerViolations(buildPlan.getLayers()));
        LaunchContractPolicy.classpathToken(buildPlan.getEntrypoint())
                .ifPresent(token -> violations.addAll(classpathViolations(buildPlan.getLayers(), token)));

        if (!violations.isEmpty()) {
            throw failure("Jib launch contract violated:\n  - " + String.join("\n  - ", violations));
        }

        requireVerifiedAgent(agentJar, expectedSha256);

        List<String> entrypoint = LaunchContractPolicy.withManagedFlags(buildPlan.getEntrypoint(), expectedSha256);
        List<LayerObject> layers = new ArrayList<>(buildPlan.getLayers());
        layers.add(agentLayer(agentJar));

        String message = """
                Platform tracing: controlled agent layer %s \
                added; container entrypoint set to %s"""
                .formatted(LaunchContractPolicy.IMAGE_AGENT_PATH, entrypoint);
        logger.log(ExtensionLogger.LogLevel.LIFECYCLE, message);

        return buildPlan.toBuilder()
                .setEntrypoint(entrypoint)
                .setLayers(layers)
                .build();
    }

    static Optional<String> registrationViolation(List<String> implementations) {
        String expected = PlatformTracingJibExtension.class.getName();
        if (implementations.size() == 1 && expected.equals(implementations.getFirst())) {
            return Optional.empty();
        }

        return Optional.of("""
                jib.pluginExtensions must contain exactly one registration of %s, found %s. \
                Remove pluginExtensions from the build script: the platform.tracing-jib plugin \
                registers its extension itself."""
                .formatted(expected, implementations));
    }

    static List<String> layerViolations(List<? extends LayerObject> layers) {
        List<String> violations = new ArrayList<>();
        for (LayerObject layer : layers) {
            if (!(layer instanceof FileEntriesLayer fileEntries)) {
                violations.add("layer '%s' has unsupported type %s; an opaque layer can hide the controlled agent"
                        .formatted(layer.getName(), layer.getClass().getName()));
                continue;
            }

            for (FileEntry entry : fileEntries.getEntries()) {
                extractionPathViolation(entry.getExtractionPath())
                        .ifPresent(reason -> violations.add("layer '%s': %s".formatted(layer.getName(), reason)));
            }
        }

        return List.copyOf(violations);
    }

    static List<String> classpathViolations(List<? extends LayerObject> layers, String classpathToken) {
        if (!classpathToken.startsWith("@")) {
            return LaunchContractPolicy.classpathElementViolations(classpathToken);
        }

        String extractionPath = classpathToken.substring(1);
        List<FileEntry> matches = new ArrayList<>();
        for (LayerObject layer : layers) {
            if (!(layer instanceof FileEntriesLayer fileEntries)) {
                continue;
            }

            for (FileEntry entry : fileEntries.getEntries()) {
                if (extractionPath.equals(entry.getExtractionPath().toString())) {
                    matches.add(entry);
                }
            }
        }

        if (matches.size() != 1) {
            return List.of("""
                    classpath file %s must have exactly one image entry, found %d; \
                    extraDirectories can shadow the Jib-generated jib-classpath-file"""
                    .formatted(extractionPath, matches.size()));
        }

        try {
            String content = Files.readString(matches.getFirst().getSourceFile());
            return LaunchContractPolicy.classpathElementViolations(content);
        } catch (IOException failure) {
            return List.of("cannot read classpath file " + extractionPath + ": " + failure.getMessage());
        }
    }

    static Optional<String> extractionPathViolation(AbsoluteUnixPath extractionPath) {
        String path = extractionPath.toString();
        for (String component : path.split("/")) {
            if (CURRENT_DIRECTORY.equals(component) || PARENT_DIRECTORY.equals(component)) {
                return Optional.of("""
                        extraction path %s is not canonical (contains '%s'); \
                        only canonical absolute Unix paths are accepted in the image"""
                        .formatted(path, component));
            }

            if (component.startsWith(WHITEOUT_PREFIX)) {
                return Optional.of("""
                        extraction path %s is an OCI whiteout entry; \
                        whiteouts could delete the controlled agent from lower layers"""
                        .formatted(path));
            }
        }

        if (OPT_DIRECTORY.equals(path)) {
            return Optional.of("""
                    extraction path %s replaces an ancestor of %s; \
                    a file at that path hides the controlled agent"""
                    .formatted(path, IMAGE_AGENT_DIRECTORY));
        }

        if (path.equals(IMAGE_AGENT_DIRECTORY) || path.startsWith(IMAGE_AGENT_DIRECTORY + "/")) {
            return Optional.of("""
                    extraction path %s is inside %s, which is owned by platform.tracing-jib; \
                    move the file elsewhere (for example under /app)"""
                    .formatted(path, IMAGE_AGENT_DIRECTORY));
        }

        return Optional.empty();
    }

    private void requireSingleRegistration(Project project) throws JibPluginExtensionException {
        List<String> implementations = project.getExtensions()
                .getByType(JibExtension.class)
                .getPluginExtensions()
                .get()
                .stream()
                .map(ExtensionParameters::getImplementation)
                .toList();

        Optional<String> violation = registrationViolation(implementations);
        if (violation.isPresent()) {
            throw failure(violation.get());
        }
    }

    private void requireVerifiedAgent(Path agentJar, String expectedSha256) throws JibPluginExtensionException {
        if (!Files.isRegularFile(agentJar)) {
            throw failure("""
                    Verified controlled agent is missing: %s. The Jib image tasks must depend on \
                    verifyControlledAgent; do not build the image with a custom task graph."""
                    .formatted(agentJar.toAbsolutePath()));
        }

        String actual;
        try {
            actual = Sha256Digest.hexOf(agentJar);
        } catch (IOException readFailure) {
            throw new JibPluginExtensionException(getClass(), "Cannot read " + agentJar.toAbsolutePath(), readFailure);
        }

        if (!expectedSha256.equals(actual)) {
            throw failure("""
                    Controlled agent %s does not match the pinned SHA-256 (expected %s, actual %s). \
                    The image build refuses unverified agent bytes."""
                    .formatted(agentJar.toAbsolutePath(), expectedSha256, actual));
        }
    }

    private static FileEntriesLayer agentLayer(Path agentJar) {
        return FileEntriesLayer.builder()
                .setName(AGENT_LAYER_NAME)
                .addEntry(agentJar, AbsoluteUnixPath.get(LaunchContractPolicy.IMAGE_AGENT_PATH))
                .build();
    }

    @SuppressWarnings("SameParameterValue")
    private String requireProperty(Map<String, String> properties, String key) throws JibPluginExtensionException {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            throw failure("""
                    Extension property %s is missing. The extension is registered by the \
                    platform.tracing-jib plugin; do not register it manually in jib.pluginExtensions."""
                    .formatted(key));
        }

        return value;
    }

    private JibPluginExtensionException failure(String message) {
        return new JibPluginExtensionException(getClass(), message);
    }
}
