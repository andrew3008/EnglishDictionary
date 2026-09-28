package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class AgentLoggingSourcePolicyTest {

    private static final List<String> FORBIDDEN_OUTPUT_TOKENS = List.of(
            "System.out",
            "System.err",
            "printStackTrace("
    );
    private static final String RAW_THROWABLE_TEXT_FIELD_TOKEN = "raw Throwable text in structured field";
    private static final String LOGGING_PACKAGE_PATH =
            "space/br1440/platform/tracing/otel/javaagent/logging/";
    private static final String AGENT_EVENT_CODE_TOKEN = "AgentEventCode";
    private static final List<String> REMOVED_LOGGING_TOKENS = List.of(
            "AgentLogFieldNamePolicy",
            "ScrubbingFailureLog",
            "isDebugEnabled("
    );
    private static final Pattern LEGACY_PUBLIC_LOGGER_METHOD = Pattern.compile(
            "\\bpublic\\s+EmitResult\\s+(error|warn|info|debug)\\s*\\("
    );
    private static final Pattern RAW_THROWABLE_MESSAGE_FIELD = Pattern.compile(
            "\\.putString\\s*\\([^;]*?\\.getMessage\\s*\\(\\s*\\)",
            Pattern.DOTALL
    );
    private static final Pattern RAW_THROWABLE_TO_STRING_FIELD = Pattern.compile(
            "\\.putString\\s*\\([^;]*?\\b(?:failure|exception|throwable|cause|error)"
                    + "\\.toString\\s*\\(\\s*\\)",
            Pattern.DOTALL
    );

    @Test
    void production_sources_use_only_the_structured_logging_path() {
        // given
        Path sourceRoot = productionSourceRoot();

        // when
        List<Violation> violations = scanSourceTree(sourceRoot);

        // then
        assertThat(violations).map(Violation::description).isEmpty();
    }

    @Test
    void scanner_rejects_each_forbidden_output_construct() {
        // given
        String fixture = """
                System.out.println("unsafe");
                System.err.println("unsafe");
                failure.printStackTrace();
                """;

        // when
        List<Violation> violations = scanContent(Path.of("fixture/DeliberateViolation.java"), fixture);

        // then
        assertThat(violations).extracting(Violation::token)
                .containsExactly("System.out", "System.err", "printStackTrace(");
    }

    @Test
    void scanner_rejects_raw_throwable_fields_but_allows_non_logging_message_use() {
        // given
        String unsafeLogging = """
                fields.putString(AgentLogField.REASON,
                        failure.getMessage());
                fields.putString(AgentLogField.FAILURE_CONTEXT, throwable.toString());
                """;
        String nonLoggingUse = """
                String diagnosticMessage = failure.getMessage();
                return new VerificationFailure(diagnosticMessage);
                """;

        // when
        List<Violation> violations = scanContent(Path.of("fixture/UnsafeLogging.java"), unsafeLogging);
        List<Violation> allowed = scanContent(Path.of("fixture/Verification.java"), nonLoggingUse);

        // then
        assertThat(violations).extracting(Violation::token)
                .containsExactly(RAW_THROWABLE_TEXT_FIELD_TOKEN, RAW_THROWABLE_TEXT_FIELD_TOKEN);
        assertThat(allowed).isEmpty();
    }

    @Test
    void scanner_enforces_the_logging_cutover_without_flagging_catalog_internals() {
        // given
        String deliberateViolation = """
                AgentEventCode code;
                AgentLogFieldNamePolicy fieldNamePolicy;
                ScrubbingFailureLog failureLog;
                logger.isDebugEnabled();
                public EmitResult error(AgentEventCode eventCode) { return null; }
                public EmitResult warn(AgentEventCode eventCode) { return null; }
                public EmitResult info(AgentEventCode eventCode) { return null; }
                public EmitResult debug(AgentEventCode eventCode) { return null; }
                """;
        String catalogInternal = """
                AgentEventCode code;
                private EmitResult warn() { return null; }
                logger.log(BootstrapLogEvent.EXTENSION_INSTALLED);
                """;

        // when
        List<Violation> violations = scanContent(
                Path.of("space/br1440/platform/tracing/otel/javaagent/DeliberateViolation.java"),
                deliberateViolation
        );
        List<Violation> allowed = scanContent(
                Path.of(LOGGING_PACKAGE_PATH, "CatalogInternal.java"),
                catalogInternal
        );

        // then
        assertThat(violations).extracting(Violation::token).containsExactly(
                AGENT_EVENT_CODE_TOKEN,
                "AgentLogFieldNamePolicy",
                "ScrubbingFailureLog",
                "isDebugEnabled(",
                AGENT_EVENT_CODE_TOKEN,
                "public EmitResult error(",
                AGENT_EVENT_CODE_TOKEN,
                "public EmitResult warn(",
                AGENT_EVENT_CODE_TOKEN,
                "public EmitResult info(",
                AGENT_EVENT_CODE_TOKEN,
                "public EmitResult debug("
        );
        assertThat(allowed).isEmpty();
    }

    private static List<Violation> scanSourceTree(Path sourceRoot) {
        List<Violation> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> violations.addAll(scanFile(sourceRoot, path)));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot scan javaagent production sources", failure);
        }
        return List.copyOf(violations);
    }

    private static List<Violation> scanFile(Path sourceRoot, Path source) {
        Path relative = sourceRoot.relativize(source);
        try {
            return scanContent(relative, Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Java source " + normalized(relative), failure);
        }
    }

    private static List<Violation> scanContent(Path relative, String content) {
        String path = normalized(relative);
        List<Violation> violations = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            for (String token : FORBIDDEN_OUTPUT_TOKENS) {
                if (line.contains(token)) {
                    violations.add(new Violation(path, index + 1, token));
                }
            }
            if (line.contains(AGENT_EVENT_CODE_TOKEN) && !path.startsWith(LOGGING_PACKAGE_PATH)) {
                violations.add(new Violation(path, index + 1, AGENT_EVENT_CODE_TOKEN));
            }
            for (String token : REMOVED_LOGGING_TOKENS) {
                if (line.contains(token)) {
                    violations.add(new Violation(path, index + 1, token));
                }
            }
            Matcher legacyMethod = LEGACY_PUBLIC_LOGGER_METHOD.matcher(line);
            while (legacyMethod.find()) {
                violations.add(new Violation(
                        path,
                        index + 1,
                        "public EmitResult " + legacyMethod.group(1) + '('
                ));
            }
        }
        addPatternViolations(violations, path, content, RAW_THROWABLE_MESSAGE_FIELD);
        addPatternViolations(violations, path, content, RAW_THROWABLE_TO_STRING_FIELD);
        return List.copyOf(violations);
    }

    private static void addPatternViolations(List<Violation> violations,
                                             String path,
                                             String content,
                                             Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            int line = 1;
            for (int index = 0; index < matcher.start(); index++) {
                if (content.charAt(index) == '\n') {
                    line++;
                }
            }
            violations.add(new Violation(path, line, RAW_THROWABLE_TEXT_FIELD_TOKEN));
        }
    }

    private static Path productionSourceRoot() {
        return repositoryRoot().resolve(
                "platform-starters-tracing/platform-tracing-otel-javaagent-extension/src/main/java");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from user.dir");
        }
        return current;
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record Violation(String path, int line, String token) {

        private String description() {
            return path + ':' + line + ": " + token;
        }
    }
}
