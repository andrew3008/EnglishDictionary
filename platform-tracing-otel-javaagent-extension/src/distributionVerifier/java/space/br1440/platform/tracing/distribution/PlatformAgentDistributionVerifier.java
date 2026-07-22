package space.br1440.platform.tracing.distribution;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Предстартовая проверка целостности контролируемого дистрибутива Java Agent.
 *
 * <p>Класс намеренно зависит только от JDK: verifier запускается до JVM приложения и не должен
 * полагаться на classpath сервиса.</p>
 */
public final class PlatformAgentDistributionVerifier {

    public static final int OK = 0;
    public static final int USAGE_ERROR = 10;
    public static final int MANIFEST_ERROR = 20;
    public static final int ARTIFACT_MISSING = 21;
    public static final int CHECKSUM_MISMATCH = 22;
    public static final int COMPATIBILITY_ERROR = 23;
    public static final int LAUNCH_CONFLICT = 24;
    public static final int IO_ERROR = 30;

    private static final String SUPPORTED_SCHEMA = "1";
    private static final String SUPPORTED_PROTOCOL = "1";
    private static final String REQUIRED_PROFILE = "platform-agent-secure-v1";
    private static final String EMBEDDED_EXTENSION_DIRECTORY = "extensions";
    private static final Set<String> CONFLICT_ENVIRONMENT_KEYS = Set.of(
            "OTEL_JAVAAGENT_EXTENSIONS",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private PlatformAgentDistributionVerifier() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
        if (args.length < 2 || !"verify".equals(args[0])) {
            err.println("Usage: java -jar platform-agent-verifier.jar verify <distribution-dir> [-- <jvm-args>]");
            return USAGE_ERROR;
        }

        Path distribution = Path.of(args[1]).toAbsolutePath().normalize();
        int conflict = verifyLaunchConfiguration(args, environment, err);
        if (conflict != OK) {
            return conflict;
        }

        try {
            return verifyDistribution(distribution, out, err);
        } catch (IOException failure) {
            err.println("E2-PREFLIGHT-IO: " + safeMessage(failure));
            return IO_ERROR;
        } catch (RuntimeException failure) {
            err.println("E2-PREFLIGHT-MANIFEST: " + safeMessage(failure));
            return MANIFEST_ERROR;
        }
    }

    private static int verifyDistribution(Path distribution, PrintStream out, PrintStream err) throws IOException {
        Path manifestPath = distribution.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            err.println("E2-PREFLIGHT-MISSING: manifest.json");
            return ARTIFACT_MISSING;
        }
        ManifestData manifest = ManifestData.parse(Files.readString(manifestPath, StandardCharsets.UTF_8));

        if (!SUPPORTED_SCHEMA.equals(manifest.schemaVersion)
                || !SUPPORTED_PROTOCOL.equals(manifest.readinessProtocolVersion)
                || !REQUIRED_PROFILE.equals(manifest.requiredCapabilityProfile)
                || !"embedded".equals(manifest.extensionLoadingMode)
                || manifest.externalExtensionParameterAllowed) {
            err.println("E2-PREFLIGHT-COMPATIBILITY: unsupported schema, protocol, profile or loading mode");
            return COMPATIBILITY_ERROR;
        }
        if (Runtime.version().feature() < manifest.minimumJava
                || Runtime.version().feature() > manifest.maximumTestedJava) {
            err.println("E2-PREFLIGHT-COMPATIBILITY: unsupported Java feature version "
                    + Runtime.version().feature());
            return COMPATIBILITY_ERROR;
        }

        Path agent = safeArtifact(distribution, manifest.agentFile);
        Path extension = safeArtifact(distribution, manifest.extensionFile);
        if (!Files.isRegularFile(agent) || !Files.isRegularFile(extension)) {
            err.println("E2-PREFLIGHT-MISSING: required Agent or extension artifact");
            return ARTIFACT_MISSING;
        }
        if (!manifest.agentSha256.equals(sha256(agent))
                || !manifest.extensionSha256.equals(sha256(extension))) {
            err.println("E2-PREFLIGHT-CHECKSUM: artifact digest mismatch");
            return CHECKSUM_MISMATCH;
        }

        int jarCompatibility = verifyJarCompatibility(agent, extension, manifest, err);
        if (jarCompatibility != OK) {
            return jarCompatibility;
        }

        out.println("E2-PREFLIGHT-OK distribution=" + manifest.distributionVersion
                + " agent=" + manifest.openTelemetryAgentVersion
                + " extension=" + manifest.platformExtensionVersion
                + " profile=" + manifest.requiredCapabilityProfile);
        return OK;
    }

    private static int verifyJarCompatibility(Path agent, Path extension, ManifestData manifest, PrintStream err)
            throws IOException {
        try (JarFile agentJar = new JarFile(agent.toFile());
             JarFile extensionJar = new JarFile(extension.toFile())) {
            String agentVersion = agentJar.getManifest().getMainAttributes()
                    .getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            String extensionVersion = extensionJar.getManifest().getMainAttributes()
                    .getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            if (!manifest.openTelemetryAgentVersion.equals(agentVersion)
                    || !manifest.platformExtensionVersion.equals(extensionVersion)) {
                err.println("E2-PREFLIGHT-COMPATIBILITY: JAR version does not match manifest");
                return COMPATIBILITY_ERROR;
            }

            String embeddedPath = EMBEDDED_EXTENSION_DIRECTORY + "/" + manifest.extensionFile;
            JarEntry embedded = agentJar.getJarEntry(embeddedPath);
            if (embedded == null) {
                err.println("E2-PREFLIGHT-MISSING: embedded platform extension");
                return ARTIFACT_MISSING;
            }
            try (InputStream stream = agentJar.getInputStream(embedded)) {
                if (!manifest.extensionSha256.equals(sha256(stream))) {
                    err.println("E2-PREFLIGHT-CHECKSUM: embedded extension differs from controlled extension");
                    return CHECKSUM_MISMATCH;
                }
            }
        }
        return OK;
    }

    private static int verifyLaunchConfiguration(String[] args, Map<String, String> environment, PrintStream err) {
        String explicitExtensions = environment.get("OTEL_JAVAAGENT_EXTENSIONS");
        if (explicitExtensions != null && !explicitExtensions.isBlank()) {
            err.println("E2-PREFLIGHT-CONFLICT: external extension loading is forbidden");
            return LAUNCH_CONFLICT;
        }
        for (String key : CONFLICT_ENVIRONMENT_KEYS) {
            String value = environment.get(key);
            if (value != null && containsConflict(value)) {
                err.println("E2-PREFLIGHT-CONFLICT: conflicting Agent option in " + key);
                return LAUNCH_CONFLICT;
            }
        }
        for (int index = 2; index < args.length; index++) {
            if (containsConflict(args[index])) {
                err.println("E2-PREFLIGHT-CONFLICT: conflicting Agent option in requested JVM arguments");
                return LAUNCH_CONFLICT;
            }
        }
        return OK;
    }

    private static boolean containsConflict(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("-javaagent:")
                || normalized.contains("otel.javaagent.extensions")
                || normalized.contains("otel_javaagent_extensions");
    }

    private static Path safeArtifact(Path distribution, String fileName) {
        Path resolved = distribution.resolve(fileName).normalize();
        if (!resolved.getParent().equals(distribution)) {
            throw new IllegalArgumentException("artifact path must be a direct child of the distribution");
        }
        return resolved;
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            return sha256(stream);
        }
    }

    private static String sha256(InputStream stream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private record ManifestData(
            String schemaVersion,
            String distributionVersion,
            String openTelemetryAgentVersion,
            String platformExtensionVersion,
            String readinessProtocolVersion,
            String requiredCapabilityProfile,
            int minimumJava,
            int maximumTestedJava,
            String extensionLoadingMode,
            boolean externalExtensionParameterAllowed,
            String agentFile,
            String agentSha256,
            String extensionFile,
            String extensionSha256) {

        private static ManifestData parse(String json) {
            String agentObject = object(json, "agent");
            String extensionObject = object(json, "extension");
            String javaObject = object(json, "javaCompatibility");
            String loadingObject = object(json, "extensionLoading");
            ManifestData data = new ManifestData(
                    string(json, "schemaVersion"),
                    string(json, "distributionVersion"),
                    string(json, "openTelemetryAgentVersion"),
                    string(json, "platformExtensionVersion"),
                    string(json, "readinessProtocolVersion"),
                    string(json, "requiredCapabilityProfile"),
                    integer(javaObject, "minimum"),
                    integer(javaObject, "maximumTested"),
                    string(loadingObject, "mode"),
                    bool(loadingObject, "externalParameterAllowed"),
                    string(agentObject, "file"),
                    string(agentObject, "sha256"),
                    string(extensionObject, "file"),
                    string(extensionObject, "sha256"));
            if (!SHA_256.matcher(data.agentSha256).matches()
                    || !SHA_256.matcher(data.extensionSha256).matches()) {
                throw new IllegalArgumentException("manifest contains an invalid SHA-256 value");
            }
            return data;
        }

        private static String object(String json, String key) {
            Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
                    + "\\\"\\s*:\\s*\\{([^{}]*)}", Pattern.DOTALL).matcher(json);
            if (!matcher.find()) {
                throw new IllegalArgumentException("missing object: " + key);
            }
            return matcher.group(1);
        }

        private static String string(String json, String key) {
            Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
                    + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
            if (!matcher.find()) {
                throw new IllegalArgumentException("missing string: " + key);
            }
            return matcher.group(1);
        }

        private static int integer(String json, String key) {
            Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
                    + "\\\"\\s*:\\s*(\\d+)").matcher(json);
            if (!matcher.find()) {
                throw new IllegalArgumentException("missing integer: " + key);
            }
            return Integer.parseInt(matcher.group(1));
        }

        private static boolean bool(String json, String key) {
            Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
                    + "\\\"\\s*:\\s*(true|false)").matcher(json);
            if (!matcher.find()) {
                throw new IllegalArgumentException("missing boolean: " + key);
            }
            return Boolean.parseBoolean(matcher.group(1));
        }
    }
}
