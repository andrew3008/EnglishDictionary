package space.br1440.platform.errorhandling.core.util;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Утилиты для классификации и диагностики {@link JsonProcessingException}.
 *
 * <p>Централизует логику для обеих Jackson-стратегий:
 * {@code core.strategy.top.JacksonExceptionStrategy} (top-level) и
 * {@code core.strategy.cause.serialization.JacksonRootCauseExceptionStrategy} (root-cause).
 *
 * <h3>Диагностические метаданные</h3>
 *
 * <h4>Общие поля (все типы {@code JsonProcessingException})</h4>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN типа исключения (alias для OpenTelemetry semantic
 *       conventions: {@code exception.type})</li>
 *   <li>{@code format} — имя формата сериализации, определяемое по типу процессора:
 *       {@code JSON}, {@code CBOR}, {@code SMILE}, {@code ION}, {@code MSGPACK},
 *       {@code YAML}, {@code XML} и т.д. ({@code JSON} — fallback)</li>
 *   <li>{@code causeMessage} — оригинальное сообщение Jackson без location appendix
 *       ({@code getOriginalMessage()}, truncated до {@value #MAX_CAUSE_MESSAGE_LENGTH} символов;
 *       alias для OpenTelemetry: {@code exception.message})</li>
 *   <li>{@code location.line} / {@code location.column} — позиция в документе</li>
 *   <li>{@code location} — human-readable дубль вида {@code "line 3, column 14"}</li>
 *   <li>{@code byteOffset} — смещение в байтах для бинарных форматов
 *       (CBOR, Smile, Ion, MessagePack); включается при {@code >= 0}</li>
 * </ul>
 *
 * <h4>Поля для {@code JsonMappingException} и подклассов</h4>
 * <ul>
 *   <li>{@code targetType} — FQCN целевого класса десериализации/сериализации</li>
 *   <li>{@code path} — JSON-путь в точечно-индексной нотации: {@code "order.items[2].price"}</li>
 *   <li>{@code pathPointer} — JSON Pointer (<a href="https://datatracker.ietf.org/doc/html/rfc6901">RFC 6901</a>):
 *       {@code "/order/items/2/price"}; машинно-читаемый вариант {@code path}</li>
 *   <li>{@code pathDepth} — глубина вложенности JSON-пути (количество элементов в path)</li>
 *   <li>{@code propertyName} — имя последнего поля в path</li>
 * </ul>
 *
 * <h4>Type-specific поля</h4>
 * <ul>
 *   <li>{@code UnrecognizedPropertyException}:
 *       {@code knownProperties} — до {@value #MAX_KNOWN_PROPERTIES} допустимых свойств
 *       целевого класса (sorted, comma-separated)</li>
 *   <li>{@code InvalidTypeIdException}:
 *       {@code typeId} (значение type discriminator из payload, truncated до
 *       {@value #MAX_TYPE_ID_LENGTH}), {@code baseType} (FQCN базового полиморфного типа
 *       из {@code @JsonTypeInfo})</li>
 * </ul>
 *
 * <h3>OpenTelemetry semantic conventions</h3>
 * <p>В дополнение к legacy-ключам ({@code exceptionClass}, {@code causeMessage})
 * выставляются alias-ключи по
 * <a href="https://opentelemetry.io/docs/specs/semconv/exceptions/exceptions-spans/">OTel exception conventions</a>:
 * {@code exception.type} и {@code exception.message}. Это упрощает интеграцию со
 * стандартными OTel-collector'ами без custom mapping-слоёв.
 *
 * <p>Все строковые поля имеют hard-limit:
 * <ul>
 *   <li>{@code causeMessage} — {@value #MAX_CAUSE_MESSAGE_LENGTH}</li>
 *   <li>{@code path} / {@code pathPointer} — {@value #MAX_PATH_LENGTH}</li>
 *   <li>{@code typeId} — {@value #MAX_TYPE_ID_LENGTH}</li>
 *   <li>{@code knownProperties} — первые {@value #MAX_KNOWN_PROPERTIES}</li>
 * </ul>
 * Это защищает от <i>log volume DoS</i> при глубоко вложенных payload (Jackson допускает
 * до 1000 уровней по умолчанию через {@code StreamReadConstraints.maxNestingDepth}).
 *
 * <h3>CWE-209: Information Exposure Through an Error Message</h3>
 * <p>{@code causeMessage} использует {@code getOriginalMessage()} — может содержать
 * имена полей и короткие excerpts значений. Допустимо для SRE-only {@code diagnosticMetadata};
 * клиент получает нейтральное сообщение через {@link #buildMessage(JsonProcessingException)}.
 * Все user-controlled поля ({@code path}, {@code propertyName}, {@code typeId}) отдельно
 * усечены и не попадают в публичный DTO.
 */
@Slf4j
@UtilityClass
public final class JacksonExceptionUtils {

    /** Максимум допустимых свойств в {@code knownProperties}. */
    public static final int MAX_KNOWN_PROPERTIES = 20;
    /** Максимальная длина {@code causeMessage}. */
    public static final int MAX_CAUSE_MESSAGE_LENGTH = 300;
    /** Максимальная длина {@code path} / {@code pathPointer}. */
    public static final int MAX_PATH_LENGTH = 1024;
    /** Максимальная длина {@code typeId}. */
    public static final int MAX_TYPE_ID_LENGTH = 100;
    /** Дефолтный domain в {@link ErrorInfoDetail}, если caller не указал свой. */
    public static final String DEFAULT_DOMAIN = "serialization";
    /** Дефолт format когда процессор не позволяет определить формат. */
    public static final String DEFAULT_FORMAT = "JSON";
    /** Префикс пакета jackson-dataformat-* модулей. */
    private static final String DATAFORMAT_PACKAGE_PREFIX = "com.fasterxml.jackson.dataformat.";
    /** Маркер для path-references, у которых нет ни fieldName, ни index (root references). */
    private static final String PATH_PLACEHOLDER = "[?]";

    /**
     * Классифицирует HTTP-статус по типу {@link JsonProcessingException}.
     *
     * <ul>
     *   <li>{@link InvalidDefinitionException} → 500: ошибка конфигурации {@code ObjectMapper}</li>
     *   <li>{@link JsonGenerationException} → 500: сбой сериализации на нашей стороне</li>
     *   <li>Всё остальное → 400: невалидный payload клиента</li>
     * </ul>
     */
    @NonNull
    public static HttpStatus classifyHttpStatus(@NonNull JsonProcessingException jpe) {
        return switch (jpe) {
            case InvalidDefinitionException ignored -> HttpStatus.INTERNAL_SERVER_ERROR;
            case JsonGenerationException ignored -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    /**
     * Возвращает нейтральное клиентское сообщение без деталей payload (CWE-209).
     */
    @NonNull
    public static String buildMessage(@NonNull JsonProcessingException jpe) {
        return switch (jpe) {
            case InvalidDefinitionException ignored -> "A JSON serialization or deserialization configuration error occurred.";
            case JsonGenerationException ignored -> "A JSON generation error occurred during serialization.";
            default -> "The JSON payload is invalid or cannot be processed.";
        };
    }

    /**
     * Строит {@link ErrorInfoDetail} с дефолтным {@code domain = "serialization"}.
     */
    @NonNull
    public static ErrorInfoDetail buildErrorDetail(@NonNull JsonProcessingException jpe,
                                                   @NonNull StackHintExtractor stackHintExtractor) {
        return buildErrorDetail(jpe, stackHintExtractor, DEFAULT_DOMAIN);
    }

    /**
     * Строит {@link ErrorInfoDetail} с явно заданным {@code domain}.
     *
     * <p>Согласно <a href="https://cloud.google.com/apis/design/errors#error_info">Google AIP-193</a>
     * {@code domain} — logical service host или error-category-namespace. Сервисы могут
     * передавать здесь FQ имя сервиса (например, {@code "billing.example.com"}) или
     * специфичную подсистему ({@code "config.json.parsing"}).
     */
    @NonNull
    public static ErrorInfoDetail buildErrorDetail(@NonNull JsonProcessingException jpe,
                                                   @NonNull StackHintExtractor stackHintExtractor,
                                                   @NonNull String domain) {
        return ErrorInfoDetail.builder()
                .reason(jpe.getClass().getName())
                .domain(domain)
                .diagnosticMetadata(buildDiagnosticMetadata(jpe, stackHintExtractor))
                .build();
    }

    @NonNull
    public static RootCauseResolution buildCauseDetails(@NonNull JsonProcessingException jpe,
                                                        @NonNull StackHintExtractor stackHintExtractor) {
        return RootCauseResolution.of(
                classifyHttpStatus(jpe),
                buildMessage(jpe),
                List.of(buildErrorDetail(jpe, stackHintExtractor))
        );
    }

    /**
     * Собирает диагностические метаданные. Метод спроектирован как <b>never-throw</b>:
     * любое непредвиденное исключение из Jackson-API локально подавляется и заменяется
     * нейтральным маркером — обработчик ошибок не должен порождать double-fault.
     */
    @NonNull
    public static Map<String, String> buildDiagnosticMetadata(@NonNull JsonProcessingException jpe,
                                                              @NonNull StackHintExtractor stackHintExtractor) {
        Map<String, String> metadata = new LinkedHashMap<>();

        String exceptionClass = jpe.getClass().getName();
        metadata.put("exceptionClass", exceptionClass);
        // OTel semantic-conventions alias: упрощает интеграцию с OTel exporters
        // без custom mapping-слоёв (https://opentelemetry.io/docs/specs/semconv/exceptions/).
        metadata.put("exception.type", exceptionClass);

        metadata.put("format", detectFormat(jpe));

        try {
            String originalMsg = jpe.getOriginalMessage();
            if (StringUtils.hasText(originalMsg)) {
                String truncated = StringUtils.truncate(originalMsg, MAX_CAUSE_MESSAGE_LENGTH);
                if (truncated != null) {
                    metadata.put("causeMessage", truncated);
                    metadata.put("exception.message", truncated);
                }
            }
        } catch (Throwable ignored) {
            // Поведение getOriginalMessage() для кастомных subclass'ов не контролируется
            // нами; never-throw garanty важнее.
        }

        enrichWithLocation(jpe, metadata);

        if (jpe instanceof JsonMappingException jme) {
            enrichWithTargetType(jme, metadata);
            enrichWithPath(jme, metadata);
            enrichWithPropertyName(jme, metadata);

            List<JsonMappingException.Reference> path = jme.getPath();
            if (path != null && !path.isEmpty()) {
                metadata.put("pathDepth", String.valueOf(path.size()));
            }
        }

        switch (jpe) {
            case UnrecognizedPropertyException upe -> enrichWithUnrecognizedProperty(upe, metadata);
            case InvalidTypeIdException ite -> enrichWithInvalidTypeId(ite, metadata);
            default -> { }
        }

        try {
            String stackHint = stackHintExtractor.extractStackHint(jpe);
            if (StringUtils.hasText(stackHint)) {
                metadata.put("stackHint", stackHint);
            }
        } catch (Throwable t) {
            log.debug("StackHintExtractor threw {} for {} -- skipping stackHint",
                    t.getClass().getName(), exceptionClass);
        }

        // Map.copyOf возвращает специализированный immutable map: быстрее
        // последующего read-only обхода и избавляет от обёртки unmodifiableMap.
        return Map.copyOf(metadata);
    }

    /**
     * Определяет имя формата сериализации по типу процессора, привязанного к исключению.
     *
     * <p>Стратегия: {@link JsonProcessingException#getProcessor()} (back-compat доступен
     * во всех Jackson 2.x) возвращает {@link JsonParser} или {@link JsonGenerator},
     * чей пакет однозначно идентифицирует формат:
     * <ul>
     *   <li>{@code com.fasterxml.jackson.core.json.*} → {@code JSON}</li>
     *   <li>{@code com.fasterxml.jackson.dataformat.cbor.*} → {@code CBOR}</li>
     *   <li>{@code com.fasterxml.jackson.dataformat.smile.*} → {@code SMILE}</li>
     *   <li>... и т.д.</li>
     * </ul>
     * При недоступности процессора возвращает {@link #DEFAULT_FORMAT} ({@code "JSON"}),
     * соответствующее общему случаю {@code jackson-core}.
     */
    @NonNull
    private static String detectFormat(@NonNull JsonProcessingException jpe) {
        Object processor;
        try {
            processor = jpe.getProcessor();
        } catch (Throwable ignored) {
            return DEFAULT_FORMAT;
        }

        if (processor == null) {
            return DEFAULT_FORMAT;
        }

        Package pkg = processor.getClass().getPackage();
        if (pkg == null) {
            return DEFAULT_FORMAT;
        }

        String name = pkg.getName();
        if (name.startsWith(DATAFORMAT_PACKAGE_PREFIX)) {
            String suffix = name.substring(DATAFORMAT_PACKAGE_PREFIX.length());
            int dot = suffix.indexOf('.');
            String fmt = (dot >= 0 ? suffix.substring(0, dot) : suffix);
            return fmt.isEmpty() ? DEFAULT_FORMAT : fmt.toUpperCase(Locale.ROOT);
        }

        return DEFAULT_FORMAT;
    }

    private static void enrichWithLocation(@NonNull JsonProcessingException jpe,
                                           @NonNull Map<String, String> metadata) {
        JsonLocation location;
        try {
            location = jpe.getLocation();
        } catch (Throwable ignored) {
            return;
        }
        if (location == null) {
            return;
        }

        int line = location.getLineNr();
        int column = location.getColumnNr();
        if (line > 0) {
            metadata.put("location.line", Integer.toString(line));
        }
        if (column > 0) {
            metadata.put("location.column", Integer.toString(column));
        }
        if (line > 0 || column > 0) {
            metadata.put("location", "line " + line + ", column " + column);
        }

        long byteOffset = location.getByteOffset();
        if (byteOffset >= 0) {
            metadata.put("byteOffset", Long.toString(byteOffset));
        }
    }

    private static void enrichWithTargetType(@NonNull JsonMappingException jme,
                                             @NonNull Map<String, String> metadata) {
        if (jme instanceof InvalidDefinitionException ide) {
            JavaType type = safeGetType(ide);
            if (type != null) {
                metadata.put("targetType", type.getRawClass().getName());
                return;
            }
        }

        if (jme instanceof MismatchedInputException mie) {
            Class<?> directTarget = safeGetTargetType(mie);
            if (directTarget != null) {
                metadata.put("targetType", directTarget.getName());
                return;
            }
        }

        try {
            List<JsonMappingException.Reference> path = jme.getPath();
            if (path != null && !path.isEmpty()) {
                Object from = path.getFirst().getFrom();
                if (from instanceof Class<?> cls) {
                    metadata.put("targetType", cls.getName());
                } else if (from != null) {
                    metadata.put("targetType", from.getClass().getName());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private static JavaType safeGetType(@NonNull InvalidDefinitionException ide) {
        try {
            return ide.getType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Class<?> safeGetTargetType(@NonNull MismatchedInputException mie) {
        try {
            return mie.getTargetType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Собирает JSON-путь в двух нотациях:
     * <ul>
     *   <li>{@code path} — точечно-индексная (Spring/Hibernate-style):
     *       {@code "order.items[2].price"}</li>
     *   <li>{@code pathPointer} — JSON Pointer (RFC 6901):
     *       {@code "/order/items/2/price"}</li>
     * </ul>
     */
    private static void enrichWithPath(@NonNull JsonMappingException jme,
                                       @NonNull Map<String, String> metadata) {
        List<JsonMappingException.Reference> path;
        try {
            path = jme.getPath();
        } catch (Throwable ignored) {
            return;
        }
        if (path == null || path.isEmpty()) {
            return;
        }

        StringBuilder dotted = new StringBuilder();
        StringBuilder pointer = new StringBuilder();

        for (JsonMappingException.Reference ref : path) {
            int index = ref.getIndex();
            String fieldName;
            try {
                fieldName = ref.getFieldName();
            } catch (Throwable ignored) {
                fieldName = null;
            }

            if (index >= 0) {
                dotted.append('[').append(index).append(']');
                pointer.append('/').append(index);
            } else if (StringUtils.hasText(fieldName)) {
                if (!dotted.isEmpty()) {
                    dotted.append('.');
                }
                dotted.append(fieldName);
                pointer.append('/').append(escapeJsonPointerToken(fieldName));
            } else {
                // Edge-case: ни fieldName, ни index. Прежде элемент молча выкидывался,
                // что давало неконсистентный путь относительно реальной структуры
                // ошибки. Placeholder -- явный сигнал, что reference присутствует,
                // но не идентифицируема.
                if (!dotted.isEmpty()) {
                    dotted.append('.');
                }
                dotted.append(PATH_PLACEHOLDER);
                pointer.append('/').append(PATH_PLACEHOLDER);
            }
        }

        String dottedPath = dotted.toString();
        if (StringUtils.hasText(dottedPath)) {
            metadata.put("path", StringUtils.truncate(dottedPath, MAX_PATH_LENGTH));
        }
        String pointerPath = pointer.toString();
        if (StringUtils.hasText(pointerPath)) {
            metadata.put("pathPointer", StringUtils.truncate(pointerPath, MAX_PATH_LENGTH));
        }
    }

    /**
     * Эскейпит спец-символы JSON Pointer (RFC 6901 §4):
     * {@code '~'} -> {@code "~0"}, {@code '/'} -> {@code "~1"}.
     * Порядок важен: '~' ДО '/'.
     */
    @NonNull
    private static String escapeJsonPointerToken(@NonNull String token) {
        if (token.indexOf('~') < 0 && token.indexOf('/') < 0) {
            return token;
        }
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static void enrichWithPropertyName(@NonNull JsonMappingException jme,
                                               @NonNull Map<String, String> metadata) {
        try {
            List<JsonMappingException.Reference> path = jme.getPath();
            if (path == null || path.isEmpty()) {
                return;
            }
            String fieldName = path.getLast().getFieldName();
            if (StringUtils.hasText(fieldName)) {
                metadata.put("propertyName", fieldName);
            }
        } catch (Throwable ignored) {
            // never-throw
        }
    }

    private static void enrichWithUnrecognizedProperty(@NonNull UnrecognizedPropertyException upe,
                                                       @NonNull Map<String, String> metadata) {
        Collection<Object> knownIds;
        try {
            knownIds = upe.getKnownPropertyIds();
        } catch (Throwable ignored) {
            return;
        }
        if (knownIds == null || knownIds.isEmpty()) {
            return;
        }

        // Defensive toString: кастомные PropertyName-реализации могут бросить.
        // Каждая отдельная id обрабатывается отдельно, не убивая всю diagnostic-метку.
        List<String> names = new ArrayList<>(knownIds.size());
        for (Object id : knownIds) {
            String s = safeToString(id);
            if (s != null) {
                names.add(s);
            }
        }
        if (names.isEmpty()) {
            return;
        }
        Collections.sort(names);

        List<String> truncated = names.size() > MAX_KNOWN_PROPERTIES
                ? names.subList(0, MAX_KNOWN_PROPERTIES)
                : names;
        String result = String.join(", ", truncated);
        if (names.size() > MAX_KNOWN_PROPERTIES) {
            result += " (+" + (names.size() - MAX_KNOWN_PROPERTIES) + " more)";
        }
        metadata.put("knownProperties", result);
    }

    private static void enrichWithInvalidTypeId(@NonNull InvalidTypeIdException ite,
                                                @NonNull Map<String, String> metadata) {
        try {
            String typeId = ite.getTypeId();
            if (StringUtils.hasText(typeId)) {
                String truncated = StringUtils.truncate(typeId, MAX_TYPE_ID_LENGTH);
                if (truncated != null) {
                    metadata.put("typeId", truncated);
                }
            }
        } catch (Throwable ignored) {
            // never-throw
        }

        try {
            JavaType baseType = ite.getBaseType();
            if (baseType != null) {
                metadata.put("baseType", baseType.getRawClass().getName());
            }
        } catch (Throwable ignored) {
            // never-throw
        }
    }

    /**
     * Безопасный {@code Object.toString()} с подавлением любых ошибок.
     * Возвращает {@code null} на любую неудачу, чтобы caller мог пропустить элемент.
     */
    @Nullable
    private static String safeToString(@Nullable Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return obj.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

}
