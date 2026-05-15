package space.br1440.platform.errorhandling.core.strategy.cause.serialization;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.core.util.CauseMetadataEnricher;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Стратегия классификации ошибок парсинга YAML (SnakeYAML) как первопричины.
 *
 * <h2>Поддерживаемые исключения (SnakeYAML)</h2>
 * <pre>
 *   org.yaml.snakeyaml.error.YAMLException       → 400  GENERIC_YAML
 *   ├── MarkedYAMLException                       → 400  (base для ошибок с позицией)
 *   │   ├── ParserException                       → 400  PARSE_ERROR
 *   │   ├── ComposerException                     → 400  COMPOSE_ERROR
 *   │   ├── ScannerException                      → 400  SCAN_ERROR
 *   │   ├── ConstructorException                  → 400  CONSTRUCT_ERROR
 *   │   └── DuplicateKeyException  (≥1.27)        → 400  DUPLICATE_KEY
 *   ├── ReaderException                           → 400  READER_ERROR
 *   └── (другие YAMLException)                    → 400  GENERIC_YAML
 * </pre>
 *
 * <h2>Dual-metadata контракт</h2>
 * <h3>clientMetadata (стабильный API для UI/DTO)</h3>
 * <ul>
 *   <li>{@code format} — всегда {@code "YAML"}.</li>
 *   <li>{@code configErrorType} — семантический тип ошибки:
 *       {@code PARSE_ERROR}, {@code COMPOSE_ERROR}, {@code SCAN_ERROR},
 *       {@code CONSTRUCT_ERROR}, {@code DUPLICATE_KEY}, {@code READER_ERROR},
 *       {@code GENERIC_YAML}.</li>
 * </ul>
 *
 * <h3>diagnosticMetadata (SRE/разработчики, не попадает в DTO)</h3>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN класса исключения ({@code getName()}).</li>
 *   <li>{@code causeMessage} — сообщение исключения (truncated, ≤{@value #MAX_MESSAGE_LENGTH}).</li>
 *   <li>{@code location} — позиция проблемы из {@code problemMark}
 *       ({@code "line N, column M"}).</li>
 *   <li>{@code contextLocation} — позиция контекста из {@code contextMark}
 *       ({@code "line N, column M"}), если обе метки доступны.</li>
 *   <li>{@code snippet} — фрагмент проблемной строки из {@code Mark.get_snippet()}.
 *       Санитизирован (CWE-209) и ограничен {@value #MAX_SNIPPET_LENGTH} символами.</li>
 *   <li>{@code yamlProblem} — описание проблемы из {@code getProblem()}.</li>
 *   <li>{@code yamlContext} — контекст ошибки из {@code getContext()}.</li>
 *   <li>{@code yamlTag} — YAML-тег из сообщения (только {@code CONSTRUCT_ERROR}),
 *       e.g. {@code "tag:yaml.org,2002:str"} или {@code "!!com.example.Foo"}.
 *       Ограничен 100 символами.</li>
 *   <li>{@code readerPosition} — позиция в потоке (только {@code ReaderException}).</li>
 *   <li>{@code readerCodePoint} — проблемный Unicode code point
 *       (только {@code ReaderException}).</li>
 *   <li>{@code rootCauseClass} — класс глубинной причины
 *       (если отличается от основного).</li>
 *   <li>{@code rootCauseMessage} — сообщение глубинной причины (truncated).</li>
 *   <li>{@code stackHint} — 1-3 значимых фрейма стектрейса.</li>
 * </ul>
 *
 * <h2>Безопасность snippet (CWE-209, CWE-117)</h2>
 * <p>
 * YAML-документы часто содержат конфиденциальные данные конфигурации
 * (пароли БД, API-ключи, токены, connection strings). Snippet из
 * {@code Mark.get_snippet()} может содержать полную строку конфигурации
 * с чувствительным значением.
 * <p>
 * Применяемые меры:
 * <ul>
 *   <li>Regex-редакция значений для ключей, указывающих на sensitive data
 *       ({@code password}, {@code secret}, {@code token}, {@code api_key} и др.).</li>
 *   <li>Удаление управляющих символов для предотвращения log injection (CWE-117).</li>
 *   <li>Ограничение длины до {@value #MAX_SNIPPET_LENGTH} символов.</li>
 *   <li>Полное сообщение исключения не включается в клиентский ответ — может содержать
 *       пути к файлам, имена переменных окружения, значения секретов из конфига.</li>
 *   <li>Имена классов из {@code ConstructorException} не включаются —
 *       раскрывают внутреннюю структуру приложения.</li>
 * </ul>
 */
public class YamlExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    public YamlExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.YAML, stackHintExtractor);
    }

    /**
     * Максимальная длина snippet из {@code Mark} для включения в metadata.
     * Ограничение предотвращает утечку больших фрагментов конфигурации (CWE-209).
     */
    static final int MAX_SNIPPET_LENGTH = 120;

    private static final int MAX_MESSAGE_LENGTH = 200;
    private static final int MAX_FIELD_LENGTH = 200;
    private static final int MAX_TAG_LENGTH = 100;

    static final Pattern SENSITIVE_KEY_VALUE = Pattern.compile("""
    (?ix)
    (
        (?:
            password | passwd | pwd | secret | token |
            api[_\\-]?key | access[_\\-]?key |
            private[_\\-]?key | credentials? |
            auth[_\\-]?(?:token|key|secret)? |
            bearer | jwt |
            connection[_\\-]?string | jdbc[_\\-]?url | dsn |
            encryption[_\\-]?key | client[_\\-]?secret |
            signing[_\\-]?key
        )
        \\s*[:=]\\s*
    )
    .+
    """);

    /**
     * Управляющие символы кроме {@code \t} (0x09), {@code \n} (0x0A), {@code \r} (0x0D).
     * Предотвращает log injection (CWE-117).
     */
    static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private static final Pattern YAML_TAG_PATTERN = Pattern.compile("tag:yaml\\.org[^\\s,;)]*|!!\\S+");

    private static final ClassLoader LOADER = YamlExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> YAML_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.yaml.snakeyaml.error.YAMLException", LOADER);

    @Nullable
    private static final Class<?> MARKED_YAML_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.yaml.snakeyaml.error.MarkedYAMLException", LOADER);

    @Nullable
    private static final Class<?> PARSER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.yaml.snakeyaml.parser.ParserException", LOADER);

    @Nullable
    private static final Class<?> COMPOSER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.yaml.snakeyaml.composer.ComposerException", LOADER);

    @Nullable
    private static final Class<?> SCANNER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.yaml.snakeyaml.scanner.ScannerException", LOADER);

    @Nullable
    private static final Class<?> CONSTRUCTOR_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.yaml.snakeyaml.constructor.ConstructorException", LOADER);

    @Nullable
    private static final Class<?> READER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.yaml.snakeyaml.reader.ReaderException", LOADER);

    @Nullable
    private static final Class<?> DUPLICATE_KEY_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.yaml.snakeyaml.error.DuplicateKeyException", LOADER);

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(YAML_EXCEPTION, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        YamlErrorType type = classify(cause);

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("serialization")
                .diagnosticMetadata(buildDiagnosticMetadata(cause, type))
                .build();

        return RootCauseResolution.of(type.httpStatus(), type.message(), List.of(info));
    }

    @NonNull
    private static YamlErrorType classify(@NonNull Throwable cause) {
        if (ClassUtils.isInstanceOf(PARSER_EXCEPTION, cause)) {
            return YamlErrorType.PARSE_ERROR;
        }

        if (ClassUtils.isInstanceOf(SCANNER_EXCEPTION, cause)) {
            return YamlErrorType.SCAN_ERROR;
        }

        if (ClassUtils.isInstanceOf(COMPOSER_EXCEPTION, cause)) {
            return YamlErrorType.COMPOSE_ERROR;
        }

        if (ClassUtils.isInstanceOf(DUPLICATE_KEY_EXCEPTION, cause)) {
            return YamlErrorType.DUPLICATE_KEY;
        }

        if (ClassUtils.isInstanceOf(CONSTRUCTOR_EXCEPTION, cause)) {
            return YamlErrorType.CONSTRUCT_ERROR;
        }

        if (ClassUtils.isInstanceOf(READER_EXCEPTION, cause)) {
            return YamlErrorType.READER_ERROR;
        }

        return YamlErrorType.GENERIC_YAML;
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull YamlErrorType type) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        String causeMessage = cause.getMessage();
        builder.putTruncatedIfPresent("causeMessage", causeMessage, MAX_MESSAGE_LENGTH);

        enrichWithMarks(cause, builder);
        enrichWithProblemAndContext(cause, builder);

        if (type == YamlErrorType.READER_ERROR) {
            enrichWithReaderDetails(cause, builder);
        }

        if (type == YamlErrorType.CONSTRUCT_ERROR) {
            enrichWithYamlTag(causeMessage, builder);
        }

        return builder
                .withRootCause(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH, MAX_MESSAGE_LENGTH)
                .withStackHint()
                .build();
    }

    /**
     * Извлекает позицию и snippet из {@code problemMark} / {@code contextMark}.
     *
     * <p>Стратегия:
     * <ol>
     *   <li>{@code problemMark} → {@code location} + {@code snippet} (санитизированный).</li>
     *   <li>Если {@code problemMark} отсутствует — fallback на {@code contextMark}.</li>
     *   <li>Если доступны оба — {@code contextMark} → дополнительное поле {@code contextLocation}.
     *       Snippet из {@code contextMark} не извлекается (удвоение leak surface).</li>
     * </ol>
     */
    private static void enrichWithMarks(@NonNull Throwable cause,
                                        @NonNull DiagnosticMetadataBuilder builder) {
        if (!ClassUtils.isInstanceOf(MARKED_YAML_EXCEPTION, cause)) {
            return;
        }

        Object problemMark = resolveMark(cause, "getProblemMark");
        Object contextMark = resolveMark(cause, "getContextMark");

        if (problemMark != null) {
            extractLocation(problemMark, builder, "location");
            extractSanitizedSnippet(problemMark, builder);
        } else if (contextMark != null) {
            extractLocation(contextMark, builder, "location");
            extractSanitizedSnippet(contextMark, builder);
        }

        if (problemMark != null && contextMark != null) {
            extractLocation(contextMark, builder, "contextLocation");
        }
    }

    private static void enrichWithProblemAndContext(@NonNull Throwable cause,
                                                    @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getProblem", problem -> {
            if (problem instanceof String yamlProblem) {
                builder.putTruncatedIfPresent("yamlProblem", yamlProblem, MAX_FIELD_LENGTH);
            }
        });

        ReflectionUtils.invokeMethod(cause, "getContext", context -> {
            if (context instanceof String yamlContext) {
                builder.putTruncatedIfPresent("yamlContext", yamlContext, MAX_FIELD_LENGTH);
            }
        });
    }

    /**
     * {@code ReaderException} содержит позицию в потоке и проблемный code point.
     * {@code getName()} намеренно не извлекается — возвращает имя потока/файла,
     * что раскрывает инфраструктурные детали (CWE-209).
     */
    private static void enrichWithReaderDetails(@NonNull Throwable cause,
                                                @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getPosition", position -> {
            if (position instanceof Integer i) {
                builder.put("readerPosition", i.toString());
            }
        });

        ReflectionUtils.invokeMethod(cause, "getCodePoint", codePoint -> {
            if (codePoint instanceof Integer cp) {
                String hex = "U+" + String.format("%04X", cp);

                try {
                    String name = Character.getName(cp);
                    if (name != null) {
                        hex += " (" + name + ")";
                    }
                } catch (IllegalArgumentException ignored) {
                }

                builder.put("readerCodePoint", hex);
            }
        });
    }

    /**
     * Извлекает YAML-тег из сообщения {@code ConstructorException}.
     *
     * <p>SnakeYAML включает тег в сообщение в форматах:
     * {@code "tag:yaml.org,2002:..."} или {@code "!!com.example.Foo"}.
     * Тег — schema-level metadata (CWE-209 safe для internal diagnostics).
     */
    private static void enrichWithYamlTag(@Nullable String causeMsg,
                                          @NonNull DiagnosticMetadataBuilder builder) {
        if (!StringUtils.hasText(causeMsg)) {
            return;
        }

        Matcher matcher = YAML_TAG_PATTERN.matcher(causeMsg);
        if (matcher.find()) {
            builder.putTruncated("yamlTag", matcher.group(), MAX_TAG_LENGTH);
        }
    }

    @Nullable
    private static Object resolveMark(@NonNull Throwable cause, @NonNull String methodName) {
        Object[] result = {null};
        ReflectionUtils.invokeMethod(cause, methodName, mark -> result[0] = mark);
        return result[0];
    }

    private static void extractLocation(@NonNull Object mark,
                                        @NonNull DiagnosticMetadataBuilder builder,
                                        @NonNull String key) {
        int[] line = {-1};
        int[] column = {-1};

        ReflectionUtils.invokeMethod(mark, "getLine", val -> {
            if (val instanceof Integer i) {
                line[0] = i;
            }
        });

        ReflectionUtils.invokeMethod(mark, "getColumn", val -> {
            if (val instanceof Integer i) {
                column[0] = i;
            }
        });

        if (line[0] >= 0 && column[0] >= 0) {
            builder.put(key, "line " + (line[0] + 1) + ", column " + (column[0] + 1));
        }
    }

    private static void extractSanitizedSnippet(@NonNull Object mark,
                                                @NonNull DiagnosticMetadataBuilder builder) {
        // SnakeYAML Mark.get_snippet() — Python-legacy naming convention, не опечатка
        ReflectionUtils.invokeMethod(mark, "get_snippet", val -> {
            if (!(val instanceof String snippet) || !StringUtils.hasText(snippet)) {
                return;
            }

            String sanitized = sanitizeSnippet(snippet);
            if (StringUtils.hasText(sanitized)) {
                builder.put("snippet", sanitized);
            }
        });
    }

    static String sanitizeSnippet(@NonNull String snippet) {
        String result = CONTROL_CHARS.matcher(snippet).replaceAll("");
        result = SENSITIVE_KEY_VALUE.matcher(result).replaceAll("$1[REDACTED]");
        return StringUtils.truncate(result, MAX_SNIPPET_LENGTH);
    }

    enum YamlErrorType {
        PARSE_ERROR(
                HttpStatus.BAD_REQUEST,
                "The YAML document contains a syntax error and could not be parsed. "
                        + "Verify indentation, special characters, and block structure."),

        COMPOSE_ERROR(
                HttpStatus.BAD_REQUEST,
                "The YAML document structure is invalid. "
                        + "Check for duplicate anchors, cyclic references, or invalid alias usage."),

        SCAN_ERROR(
                HttpStatus.BAD_REQUEST,
                "The YAML document contains invalid characters or encoding. "
                        + "Verify the document encoding and escape sequences."),

        CONSTRUCT_ERROR(
                HttpStatus.BAD_REQUEST,
                "The YAML document cannot be mapped to the expected structure. "
                        + "Verify that all fields, types, and tags conform to the expected schema."),

        DUPLICATE_KEY(
                HttpStatus.BAD_REQUEST,
                "The YAML document contains duplicate keys within the same mapping. "
                        + "Each key must be unique per YAML specification (Section 3.2.1.3)."),

        READER_ERROR(
                HttpStatus.BAD_REQUEST,
                "The YAML document contains invalid characters or an unsupported encoding. "
                        + "Verify the document uses valid UTF-8/UTF-16 encoding."),

        GENERIC_YAML(
                HttpStatus.BAD_REQUEST,
                "The YAML document is invalid or cannot be processed.");

        private final HttpStatus httpStatus;
        private final String message;

        YamlErrorType(@NonNull HttpStatus httpStatus, @NonNull String message) {
            this.httpStatus = httpStatus;
            this.message = message;
        }

        @NonNull
        HttpStatus httpStatus() {
            return httpStatus;
        }

        @NonNull
        String message() {
            return message;
        }
    }
}
