package space.br1440.platform.tracing.jmx.contract.tool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class JmxWireContractGenerator {

    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = parseOptions(arguments);
        String mode = requiredOption(options, "mode");
        Path manifestPath = Path.of(requiredOption(options, "manifest"));
        ContractDocument contract = ContractDocumentFactory.load(manifestPath);

        switch (mode) {
            case "validate" -> System.out.println("JMX wire contract is valid: " + contract.schemaDigest());
            case "generate" -> generate(contract, Path.of(requiredOption(options, "output")));
            case "source-gate" -> JmxWireLiteralSourceGate.verify(
                    contract,
                    sourceRoots(requiredOption(options, "source-roots"))
            );

            case "verify" -> verify(
                    contract,
                    Path.of(requiredOption(options, "output")),
                    Path.of(requiredOption(options, "report"))
            );

            default -> throw new IllegalArgumentException("Unsupported generator mode: " + mode);
        }
    }

    private static void generate(ContractDocument contract, Path outputRoot) throws IOException {
        Path temporaryRoot = siblingTemporaryDirectory(outputRoot, "generate");
        try {
            JavaContractRenderer.render(contract, temporaryRoot);
            replaceDirectory(temporaryRoot, outputRoot);
        } finally {
            deleteDirectoryIfExists(temporaryRoot);
        }

        System.out.println("Generated JMX wire contract: " + outputRoot);
    }

    private static void verify(ContractDocument contract, Path outputRoot, Path reportPath) throws IOException {
        Path first = siblingTemporaryDirectory(outputRoot, "verify-a");
        Path second = siblingTemporaryDirectory(outputRoot, "verify-b");
        try {
            JavaContractRenderer.render(contract, first);
            JavaContractRenderer.render(contract, second);
            assertTreesEqual(first, second, "Generated JMX contract is not deterministic");
            assertTreesEqual(first, outputRoot, "Generated JMX contract is stale");
            assertPlaneParity(first.resolve("extension"), first.resolve("application"));
            writeReport(contract, reportPath);
        } finally {
            deleteDirectoryIfExists(first);
            deleteDirectoryIfExists(second);
        }

        System.out.println("Verified JMX wire contract: " + contract.schemaDigest());
    }

    private static void assertPlaneParity(Path extensionRoot, Path applicationRoot) throws IOException {
        Map<String, String> extension = paritySurface(extensionRoot);
        Map<String, String> application = paritySurface(applicationRoot);
        if (!extension.equals(application)) {
            throw new IllegalStateException("Generated extension and application contract descriptors differ");
        }
    }

    private static Map<String, String> paritySurface(Path planeRoot) throws IOException {
        Map<String, String> result = new HashMap<>();
        try (Stream<Path> files = Files.walk(planeRoot)) {
            files.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        try {
                            String relative = planeRoot.relativize(path).toString().replace('\\', '/');
                            String key = relative.substring(relative.lastIndexOf('/') + 1);
                            String content = Files.readString(path, StandardCharsets.UTF_8)
                                    .replaceFirst("(?m)^package [^;]+;\n", "")
                                    .replaceAll(
                                            "space\\.br1440\\.platform\\.tracing\\.(otel\\.javaagent|autoconfigure)"
                                                    + "\\.jmx\\.contract\\.generated",
                                            ""
                                    );
                            result.put(key, content);
                        } catch (IOException exception) {
                            throw new ContractIoRuntimeException(exception);
                        }
                    });
        } catch (ContractIoRuntimeException exception) {
            throw exception.ioException();
        }

        return result;
    }

    private static void writeReport(ContractDocument contract, Path reportPath) throws IOException {
        Files.createDirectories(reportPath.getParent());
        Properties properties = new Properties();
        properties.setProperty("schema.id", contract.schemaId());
        properties.setProperty("schema.sha256", contract.schemaDigest());
        properties.setProperty("descriptors.sha256", contract.descriptorDigest());
        properties.setProperty("deterministic", "true");
        properties.setProperty("planeParity", "true");

        List<String> names = new ArrayList<>(properties.stringPropertyNames());
        names.sort(Comparator.naturalOrder());

        StringBuilder output = new StringBuilder();
        for (String name : names) {
            output.append(name).append('=').append(properties.getProperty(name)).append('\n');
        }

        writeUtf8(reportPath, output.toString());
    }

    private static void assertTreesEqual(Path expected, Path actual, String message) throws IOException {
        Map<String, byte[]> expectedFiles = readTree(expected);
        Map<String, byte[]> actualFiles = readTree(actual);
        if (!expectedFiles.keySet().equals(actualFiles.keySet())) {
            throw new IllegalStateException(message + ": file sets differ");
        }

        for (String relative : expectedFiles.keySet()) {
            if (!java.util.Arrays.equals(expectedFiles.get(relative), actualFiles.get(relative))) {
                throw new IllegalStateException(message + ": " + relative);
            }
        }
    }

    private static Map<String, byte[]> readTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Generated JMX contract directory is absent: " + root);
        }

        Map<String, byte[]> result = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        try {
                            result.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
                        } catch (IOException exception) {
                            throw new ContractIoRuntimeException(exception);
                        }
                    });
        } catch (ContractIoRuntimeException exception) {
            throw exception.ioException();
        }

        return result;
    }

    private static Path siblingTemporaryDirectory(Path outputRoot, String suffix) throws IOException {
        Path parent = outputRoot.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Output directory must have a parent: " + outputRoot);
        }

        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, ".jmx-contract-" + suffix + '-');
    }

    private static void replaceDirectory(Path source, Path target) throws IOException {
        deleteDirectoryIfExists(target);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteDirectoryIfExists(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }

    static void writeUtf8(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseOptions(String[] arguments) {
        Map<String, String> result = new HashMap<>();
        for (String argument : arguments) {
            if (!argument.startsWith("--") || !argument.contains("=")) {
                throw new IllegalArgumentException("Invalid generator argument: " + argument);
            }

            int separator = argument.indexOf('=');
            String name = argument.substring(2, separator);
            String value = argument.substring(separator + 1);
            if (result.put(name, value) != null) {
                throw new IllegalArgumentException("Duplicate generator argument: " + name);
            }
        }

        return result;
    }

    private static List<Path> sourceRoots(String encodedRoots) {
        List<Path> result = new ArrayList<>();
        for (String encodedRoot : encodedRoots.split(Pattern.quote(File.pathSeparator))) {
            if (!encodedRoot.isBlank()) {
                result.add(Path.of(encodedRoot));
            }
        }

        if (result.isEmpty()) {
            throw new IllegalArgumentException("JMX source-gate directories must not be empty");
        }

        return List.copyOf(result);
    }

    private static String requiredOption(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing generator argument: " + name);
        }

        return value;
    }

    private static final class ContractIoRuntimeException extends RuntimeException {

        private final IOException ioException;

        private ContractIoRuntimeException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }

        private IOException ioException() {
            return ioException;
        }
    }
}
