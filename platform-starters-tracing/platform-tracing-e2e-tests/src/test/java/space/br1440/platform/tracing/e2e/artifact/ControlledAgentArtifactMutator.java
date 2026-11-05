package space.br1440.platform.tracing.e2e.artifact;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import space.br1440.platform.common.util.StringUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ControlledAgentArtifactMutator {

    private static final String EXTENSION_ENTRY = "extensions/platform-tracing-otel-javaagent-extension.jar";
    private static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";
    public static final String EXTENSION_BUILD_METADATA_ENTRY =
            "space/br1440/platform/tracing/otel/javaagent/readiness/platform-extension-build.properties";
    private static final String RUNTIME_ENVIRONMENT_ENTRY =
            "space/br1440/platform/tracing/otel/javaagent/bootstrap/verification/ControlledAgentVerifier$CurrentJvm.class";

    private static final String STALE_TOPOLOGY_METHOD = "sdkDefiningLoader";
    private static final String STALE_TOPOLOGY_METHOD_DESCRIPTOR = "()Ljava/lang/ClassLoader;";
    private static final String AGENT_LISTENER_DESCRIPTOR =
            "META-INF/services/io.opentelemetry.javaagent.extension.AgentListener";
    private static final String PLATFORM_LISTENER =
            "space.br1440.platform.tracing.otel.javaagent.bootstrap.ActivationAgentListener";
    private static final String POST_SDK_FAILURE_LISTENER =
            "space.br1440.platform.tracing.e2e.startup.FailingBeforeActivationAgentListener";
    private static final String POST_READY_FAILURE_LISTENER =
            "space.br1440.platform.tracing.e2e.startup.FatalAfterReadyAgentListener";
    private static final String POST_READY_FAILURE =
            "space.br1440.platform.tracing.e2e.startup.PostReadyLinkageFailure";

    public static Path withFailingBeforeActivationListener(Path source,
                                                           Path target,
                                                           Path listenerFixture) throws IOException {
        return withAgentListenerFixture(source, target, listenerFixture,
                POST_SDK_FAILURE_LISTENER, List.of(POST_SDK_FAILURE_LISTENER));
    }

    public static Path withFatalAfterReadyListener(Path source,
                                                   Path target,
                                                   Path listenerFixture) throws IOException {
        return withAgentListenerFixture(source, target, listenerFixture,
                POST_READY_FAILURE_LISTENER, List.of(POST_READY_FAILURE_LISTENER, POST_READY_FAILURE));
    }

    private static Path withAgentListenerFixture(Path source,
                                                 Path target,
                                                 Path listenerFixture,
                                                 String listener,
                                                 List<String> fixtureClasses) throws IOException {
        JarArchive.Content outer = JarArchive.read(Files.readAllBytes(source));
        JarArchive.Content nested = JarArchive.read(JarArchive.requiredEntry(outer.entries(), EXTENSION_ENTRY));
        JarArchive.Content fixture = JarArchive.read(Files.readAllBytes(listenerFixture));
        for (String fixtureClass : fixtureClasses) {
            String classEntry = fixtureClass.replace('.', '/') + ".class";
            byte[] classBytes = JarArchive.requiredEntry(fixture.entries(), classEntry);
            if (nested.entries().putIfAbsent(classEntry, classBytes) != null) {
                throw new IllegalArgumentException("Listener fixture class is already packaged in the extension: "
                        + fixtureClass);
            }
        }

        String descriptor = new String(
                JarArchive.requiredEntry(nested.entries(), AGENT_LISTENER_DESCRIPTOR), StandardCharsets.UTF_8);
        List<String> providers = descriptor.lines().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        if (providers.stream().filter(PLATFORM_LISTENER::equals).count() != 1L
                || providers.contains(listener)) {
            throw new IllegalArgumentException("Expected exactly one platform listener and no failure fixture");
        }
        String mergedDescriptor = descriptor + (descriptor.endsWith("\n") ? "" : "\n") + listener + "\n";
        nested.entries().put(AGENT_LISTENER_DESCRIPTOR, mergedDescriptor.getBytes(StandardCharsets.UTF_8));

        byte[] mutatedExtension = JarArchive.write(nested);
        outer.entries().put(EXTENSION_ENTRY, mutatedExtension);
        Properties identity = loadIdentity(outer);
        identity.setProperty("extension.sha256", sha256(mutatedExtension));
        outer.entries().put(IDENTITY_ENTRY, ControlledAgentIdentityCodec.canonicalize(identity));
        JarArchive.write(target, outer);
        return target;
    }

    public static Path withoutPackagedExtension(Path source,
                                                Path target) throws IOException {
        JarArchive.Content outer = JarArchive.read(Files.readAllBytes(source));
        if (outer.entries().remove(EXTENSION_ENTRY) == null) {
            throw new IllegalArgumentException(
                    "Controlled agent packaged extension is already missing");
        }

        JarArchive.write(target, outer);
        return target;
    }

    public static Path withoutExtensionEntry(Path source,
                                             Path target,
                                             String extensionEntryToRemove) throws IOException {
        JarArchive.Content outer = JarArchive.read(Files.readAllBytes(source));
        byte[] extension = JarArchive.requiredEntry(outer.entries(), EXTENSION_ENTRY);
        JarArchive.Content nested = JarArchive.read(extension);
        if (nested.entries().remove(extensionEntryToRemove) == null) {
            throw new IllegalArgumentException(
                    "Controlled agent extension entry is missing: " + extensionEntryToRemove);
        }

        byte[] mutatedExtension = JarArchive.write(nested);
        outer.entries().put(EXTENSION_ENTRY, mutatedExtension);

        Properties identity = loadIdentity(outer);
        identity.setProperty("extension.sha256", sha256(mutatedExtension));
        outer.entries().put(IDENTITY_ENTRY, ControlledAgentIdentityCodec.canonicalize(identity));
        JarArchive.write(target, outer);
        return target;
    }

    public static Path withSeparatelyVersionedStaleTopologyApi(Path source,
                                                               Path target,
                                                               String staleVersion) throws IOException {
        if (StringUtils.isNullOrBlank(staleVersion)) {
            throw new IllegalArgumentException("Stale fixture version must not be blank");
        }

        JarArchive.Content outer = JarArchive.read(Files.readAllBytes(source));
        JarArchive.Content nested = JarArchive.read(JarArchive.requiredEntry(outer.entries(), EXTENSION_ENTRY));
        byte[] runtimeEnvironment = JarArchive.requiredEntry(nested.entries(), RUNTIME_ENVIRONMENT_ENTRY);
        nested.entries().put(
                RUNTIME_ENVIRONMENT_ENTRY,
                ClassFileMethodRemover.removeMethod(runtimeEnvironment, STALE_TOPOLOGY_METHOD, STALE_TOPOLOGY_METHOD_DESCRIPTOR)
        );

        nested.manifest().getMainAttributes().putValue("Implementation-Version", staleVersion);
        setExtensionBuildVersion(nested, staleVersion);
        byte[] mutatedExtension = JarArchive.write(nested);
        outer.entries().put(EXTENSION_ENTRY, mutatedExtension);

        Properties identity = loadIdentity(outer);
        identity.setProperty("distribution.version", staleVersion);
        identity.setProperty("extension.sha256", sha256(mutatedExtension));
        outer.entries().put(IDENTITY_ENTRY, ControlledAgentIdentityCodec.canonicalize(identity));
        JarArchive.write(target, outer);
        return target;
    }

    public static Path withMismatchedExtensionBuildVersion(Path source,
                                                           Path target,
                                                           String mismatchedVersion) throws IOException {
        if (StringUtils.isNullOrBlank(mismatchedVersion)) {
            throw new IllegalArgumentException("Mismatched extension version must not be blank");
        }

        JarArchive.Content outer = JarArchive.read(Files.readAllBytes(source));
        JarArchive.Content nested = JarArchive.read(
                JarArchive.requiredEntry(outer.entries(), EXTENSION_ENTRY)
        );
        setExtensionBuildVersion(nested, mismatchedVersion);
        byte[] mutatedExtension = JarArchive.write(nested);
        outer.entries().put(EXTENSION_ENTRY, mutatedExtension);

        Properties identity = loadIdentity(outer);
        identity.setProperty("extension.sha256", sha256(mutatedExtension));
        outer.entries().put(IDENTITY_ENTRY, ControlledAgentIdentityCodec.canonicalize(identity));
        JarArchive.write(target, outer);
        return target;
    }

    public static Properties identity(Path source) throws IOException {
        JarArchive.Content outer = JarArchive.read(Files.readAllBytes(source));
        return loadIdentity(outer);
    }

    public static byte[] packagedExtension(Path source) throws IOException {
        return JarArchive.requiredEntry(JarArchive.read(Files.readAllBytes(source)).entries(), EXTENSION_ENTRY);
    }

    public static String sha256(Path source) throws IOException {
        return sha256(Files.readAllBytes(source));
    }

    public static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Properties loadIdentity(JarArchive.Content outer) throws IOException {
        byte[] entry = JarArchive.requiredEntry(outer.entries(), IDENTITY_ENTRY);
        return ControlledAgentIdentityCodec.load(entry);
    }

    private static Properties loadProperties(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        return properties;
    }

    private static byte[] storeProperties(Properties properties) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, null);
        return output.toByteArray();
    }

    private static void setExtensionBuildVersion(JarArchive.Content nested,
                                                 String version) throws IOException {
        Properties extensionMetadata = loadProperties(
                JarArchive.requiredEntry(nested.entries(), EXTENSION_BUILD_METADATA_ENTRY)
        );

        extensionMetadata.setProperty("version", version);
        nested.entries().put(EXTENSION_BUILD_METADATA_ENTRY, storeProperties(extensionMetadata));
    }
}
