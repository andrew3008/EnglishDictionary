package space.br1440.platform.errorhandling.core.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("JacksonExceptionUtils")
class JacksonExceptionUtilsTest {

    private final StackHintExtractor stackHintExtractor = new StackHintExtractor(false, List.of());

    @Nested
    @DisplayName("classifyHttpStatus")
    class ClassifyHttpStatus {

        @Test
        @DisplayName("InvalidDefinitionException -> 500 (server-side configuration)")
        void invalidDefinition_500() {
            JsonProcessingException jpe = invalidDefinition();
            assertThat(JacksonExceptionUtils.classifyHttpStatus(jpe))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("JsonGenerationException -> 500 (server-side serialization)")
        void jsonGeneration_500() {
            JsonProcessingException jpe = new JsonGenerationException("write failure", (JsonGenerator) null);
            assertThat(JacksonExceptionUtils.classifyHttpStatus(jpe))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("JsonParseException -> 400 (client-supplied invalid JSON)")
        void jsonParse_400() throws Exception {
            JsonProcessingException jpe = parseInvalidJson("{ not json }");
            assertThat(JacksonExceptionUtils.classifyHttpStatus(jpe))
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("MismatchedInputException -> 400")
        void mismatchedInput_400() {
            MismatchedInputException mie = MismatchedInputException.from(
                    (JsonParser) null, String.class, "type mismatch");
            assertThat(JacksonExceptionUtils.classifyHttpStatus(mie))
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("buildMessage")
    class BuildMessage {

        @Test
        @DisplayName("Generic JsonProcessingException -> client-neutral payload message")
        void generic_neutralMessage() {
            JsonProcessingException jpe = new JsonProcessingException("internal payload x") {};
            assertThat(JacksonExceptionUtils.buildMessage(jpe))
                    .doesNotContain("internal payload x")
                    .contains("payload");
        }

        @Test
        @DisplayName("InvalidDefinitionException message describes configuration error")
        void invalidDefinition_configMessage() {
            JsonProcessingException jpe = invalidDefinition();
            assertThat(JacksonExceptionUtils.buildMessage(jpe))
                    .contains("configuration");
        }

        @Test
        @DisplayName("JsonGenerationException message describes serialization error")
        void jsonGeneration_serializationMessage() {
            JsonProcessingException jpe = new JsonGenerationException("write failure", (JsonGenerator) null);
            assertThat(JacksonExceptionUtils.buildMessage(jpe))
                    .containsIgnoringCase("generation");
        }
    }

    @Nested
    @DisplayName("buildDiagnosticMetadata: common fields")
    class CommonFields {

        @Test
        @DisplayName("Always includes exceptionClass and OTel alias exception.type")
        void exceptionClass_otelAlias() {
            JsonProcessingException jpe = new JsonProcessingException("err") {};
            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(jpe, stackHintExtractor);
            assertThat(meta).containsEntry("exceptionClass", jpe.getClass().getName());
            assertThat(meta).containsEntry("exception.type", jpe.getClass().getName());
        }

        @Test
        @DisplayName("Default format is JSON when processor is null")
        void defaultFormat_json() {
            JsonProcessingException jpe = new JsonProcessingException("err") {};
            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(jpe, stackHintExtractor);
            assertThat(meta).containsEntry("format", "JSON");
        }

        @Test
        @DisplayName("causeMessage and OTel alias exception.message contain truncated original message")
        void causeMessage_otelAlias_truncated() {
            String big = "x".repeat(JacksonExceptionUtils.MAX_CAUSE_MESSAGE_LENGTH + 100);
            JsonProcessingException jpe = new JsonProcessingException(big) {};
            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(jpe, stackHintExtractor);
            assertThat(meta.get("causeMessage")).isNotNull();
            assertThat(meta.get("causeMessage").length())
                    .isLessThanOrEqualTo(JacksonExceptionUtils.MAX_CAUSE_MESSAGE_LENGTH + " [TRUNCATED]".length());
            assertThat(meta).containsEntry("exception.message", meta.get("causeMessage"));
        }

        @Test
        @DisplayName("Empty/null original message is omitted (no empty fields)")
        void emptyMessage_omitted() {
            JsonProcessingException jpe = new JsonProcessingException("") {};
            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(jpe, stackHintExtractor);
            assertThat(meta).doesNotContainKey("causeMessage");
            assertThat(meta).doesNotContainKey("exception.message");
        }

        @Test
        @DisplayName("Returned map is immutable")
        void mapIsImmutable() {
            JsonProcessingException jpe = new JsonProcessingException("err") {};
            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(jpe, stackHintExtractor);
            assertThatCode(() -> meta.put("malicious", "v"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("buildDiagnosticMetadata: location")
    class LocationFields {

        @Test
        @DisplayName("Real JsonParseException location is captured (line/column/byteOffset)")
        void realLocation_capturedFromParse() throws Exception {
            JsonProcessingException jpe = parseInvalidJson("[1, 2, 3,\nINVALID, 5]");
            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(jpe, stackHintExtractor);
            assertThat(meta).containsKey("location.line");
            assertThat(meta).containsKey("location.column");
            assertThat(meta).containsKey("location");
            assertThat(meta.get("location")).matches("line \\d+, column \\d+");
        }

        @Test
        @DisplayName("byteOffset is preserved when known (regression: condition was > 0, dropping valid 0)")
        void byteOffset_preservedAtZero() {
            // Прямая проверка инварианта condition >= 0 через handcrafted
            // JsonParseException с location, у которого byteOffset = 0.
            // Это типичный случай для бинарных форматов (CBOR/Smile),
            // где первый байт = type tag и фигурирует в ошибках.
            com.fasterxml.jackson.core.JsonLocation loc =
                    new com.fasterxml.jackson.core.JsonLocation(
                            /* sourceRef */ null, /* totalBytes */ 0L,
                            /* totalChars */ -1L, /* lineNr */ 1, /* columnNr */ 1);
            JsonProcessingException jpe = new com.fasterxml.jackson.core.JsonParseException(
                    null, "tag byte invalid at offset 0", loc);

            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(jpe, stackHintExtractor);
            assertThat(meta).containsEntry("byteOffset", "0");
        }
    }

    @Nested
    @DisplayName("buildDiagnosticMetadata: targetType")
    class TargetTypeFields {

        @Test
        @DisplayName("InvalidDefinitionException uses ide.getType().getRawClass() (no reflection)")
        void invalidDefinition_typeFromJavaType() {
            InvalidDefinitionException ide = invalidDefinition();
            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(ide, stackHintExtractor);
            assertThat(meta).containsEntry("targetType", String.class.getName());
        }

        @Test
        @DisplayName("MismatchedInputException uses mie.getTargetType()")
        void mismatchedInput_targetType() {
            MismatchedInputException mie = MismatchedInputException.from(
                    (JsonParser) null, Integer.class, "expected number");
            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(mie, stackHintExtractor);
            assertThat(meta).containsEntry("targetType", Integer.class.getName());
        }
    }

    @Nested
    @DisplayName("buildDiagnosticMetadata: path / pathPointer")
    class PathFields {

        @Test
        @DisplayName("Mixed field/index path serialises both formats")
        void mixedPath_bothFormats() {
            // JsonMappingException.Reference имеет конструкторы (Object, String) и
            // (Object, int) -- но не оба сразу. Поэтому "items[0]" моделируем как
            // две отдельные prependPath: индекс ПЕРЕД полем, т.к. prependPath
            // вставляет в начало. Итог: order, [2], items, [0].
            MismatchedInputException mie = MismatchedInputException.from(
                    (JsonParser) null, String.class, "wrong");
            mie.prependPath(new JsonMappingException.Reference(new Object(), 0));
            mie.prependPath(new JsonMappingException.Reference(new Object(), "items"));
            mie.prependPath(new JsonMappingException.Reference(new Object(), 2));
            mie.prependPath(new JsonMappingException.Reference(new Object(), "order"));

            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(mie, stackHintExtractor);
            assertThat(meta.get("path")).isEqualTo("order[2].items[0]");
            assertThat(meta.get("pathPointer")).isEqualTo("/order/2/items/0");
            assertThat(meta).containsEntry("pathDepth", "4");
        }

        @Test
        @DisplayName("Field name with '/' or '~' is escaped per RFC 6901")
        void rfc6901_specialCharsEscaped() {
            MismatchedInputException mie = MismatchedInputException.from(
                    (JsonParser) null, String.class, "wrong");
            mie.prependPath(new JsonMappingException.Reference(new Object(), "field/with~slash"));

            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(mie, stackHintExtractor);
            // ~ заменяется на ~0 ПЕРВЫМ, иначе ~1 (для /) затёрло бы ~ обратной заменой
            assertThat(meta.get("pathPointer")).isEqualTo("/field~1with~0slash");
        }

        @Test
        @DisplayName("Edge reference (no name, no index) uses [?] placeholder, not silent skip")
        void edgeReference_placeholder() {
            MismatchedInputException mie = MismatchedInputException.from(
                    (JsonParser) null, String.class, "wrong");
            // Reference только с from-объектом, без fieldName и без index (-1):
            // именно этот случай раньше тихо выкидывал элемент пути.
            mie.prependPath(new JsonMappingException.Reference(new Object()));
            mie.prependPath(new JsonMappingException.Reference(new Object(), "root"));

            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(mie, stackHintExtractor);
            assertThat(meta.get("path")).isEqualTo("root.[?]");
            assertThat(meta.get("pathPointer")).isEqualTo("/root/[?]");
        }

        @Test
        @DisplayName("Very long path is truncated to MAX_PATH_LENGTH (DoS guard)")
        void longPath_truncated() {
            MismatchedInputException mie = MismatchedInputException.from(
                    (JsonParser) null, String.class, "wrong");
            // 200 уровней вложенности по 10 символов -> 2000+ chars
            for (int i = 0; i < 200; i++) {
                mie.prependPath(new JsonMappingException.Reference(new Object(), "fieldName" + i));
            }

            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(mie, stackHintExtractor);
            String path = meta.get("path");
            String pointer = meta.get("pathPointer");
            assertThat(path.length())
                    .isLessThanOrEqualTo(JacksonExceptionUtils.MAX_PATH_LENGTH + " [TRUNCATED]".length());
            assertThat(pointer.length())
                    .isLessThanOrEqualTo(JacksonExceptionUtils.MAX_PATH_LENGTH + " [TRUNCATED]".length());
            assertThat(path).endsWith("[TRUNCATED]");
        }

        @Test
        @DisplayName("propertyName takes the LAST reference field name")
        void propertyName_lastFieldOnly() {
            MismatchedInputException mie = MismatchedInputException.from(
                    (JsonParser) null, String.class, "wrong");
            mie.prependPath(new JsonMappingException.Reference(new Object(), "lastField"));
            mie.prependPath(new JsonMappingException.Reference(new Object(), "firstField"));

            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(mie, stackHintExtractor);
            assertThat(meta).containsEntry("propertyName", "lastField");
        }
    }

    @Nested
    @DisplayName("buildDiagnosticMetadata: UnrecognizedPropertyException")
    class UnrecognizedProperty {

        @Test
        @DisplayName("knownProperties is sorted, comma-separated, limited")
        void knownProperties_sortedLimited() {
            List<Object> ids = new ArrayList<>();
            for (int i = 0; i < JacksonExceptionUtils.MAX_KNOWN_PROPERTIES + 5; i++) {
                ids.add("prop_" + String.format("%02d", i));
            }
            UnrecognizedPropertyException upe = unrecognizedProperty("useId", ids);

            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(upe, stackHintExtractor);
            String known = meta.get("knownProperties");
            assertThat(known).isNotNull();
            assertThat(known).contains("prop_00");
            assertThat(known).contains("more)");
        }

        @Test
        @DisplayName("Defensive: hostile id.toString() does not propagate to caller")
        void hostileToString_swallowed() {
            Object hostile = new Object() {
                @Override public String toString() {
                    throw new RuntimeException("hostile");
                }
            };
            List<Object> ids = new ArrayList<>(List.of(hostile, "userId", "userName"));
            UnrecognizedPropertyException upe = unrecognizedProperty("useId", ids);

            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(upe, stackHintExtractor);
            // Hostile element ignored, surviving names processed.
            assertThat(meta.get("knownProperties")).contains("userId").contains("userName");
        }
    }

    @Nested
    @DisplayName("buildDiagnosticMetadata: InvalidTypeIdException")
    class InvalidTypeId {

        @Test
        @DisplayName("typeId truncated to MAX_TYPE_ID_LENGTH")
        void typeId_truncated() {
            String huge = "T".repeat(JacksonExceptionUtils.MAX_TYPE_ID_LENGTH + 50);
            JavaType base = TypeFactory.defaultInstance().constructType(Object.class);
            InvalidTypeIdException ite = InvalidTypeIdException.from(
                    (JsonParser) null, "type lookup failed", base, huge);

            Map<String, String> meta = JacksonExceptionUtils.buildDiagnosticMetadata(ite, stackHintExtractor);
            assertThat(meta.get("typeId").length())
                    .isLessThanOrEqualTo(JacksonExceptionUtils.MAX_TYPE_ID_LENGTH + " [TRUNCATED]".length());
            assertThat(meta).containsEntry("baseType", Object.class.getName());
        }
    }

    @Nested
    @DisplayName("buildErrorDetail: domain customisation")
    class DomainCustomisation {

        @Test
        @DisplayName("Default overload uses 'serialization' domain")
        void defaultDomain_serialization() {
            JsonProcessingException jpe = new JsonProcessingException("x") {};
            ErrorInfoDetail detail = JacksonExceptionUtils.buildErrorDetail(jpe, stackHintExtractor);
            assertThat(detail.getDomain()).isEqualTo("serialization");
        }

        @Test
        @DisplayName("Explicit domain overload propagates value (AIP-193 alignment)")
        void customDomain_propagated() {
            JsonProcessingException jpe = new JsonProcessingException("x") {};
            ErrorInfoDetail detail = JacksonExceptionUtils.buildErrorDetail(
                    jpe, stackHintExtractor, "billing.example.com");
            assertThat(detail.getDomain()).isEqualTo("billing.example.com");
        }
    }

    @Nested
    @DisplayName("Robustness: never-throw guarantee")
    class NeverThrow {

        @Test
        @DisplayName("Hostile getOriginalMessage() does not propagate")
        void hostileOriginalMessage() {
            JsonProcessingException jpe = new JsonProcessingException("x") {
                @Override public String getOriginalMessage() {
                    throw new RuntimeException("boom");
                }
            };
            assertThatCode(() -> JacksonExceptionUtils.buildDiagnosticMetadata(jpe, stackHintExtractor))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Hostile StackHintExtractor does not propagate")
        void hostileStackHint() {
            // StackHintExtractor final -> используем Mockito stub. Проверяем именно
            // never-throw semantics: исключение из extractStackHint не должно
            // оборвать сборку diagnostic-метаданных.
            StackHintExtractor evil = org.mockito.Mockito.mock(StackHintExtractor.class);
            org.mockito.Mockito.when(evil.extractStackHint(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new RuntimeException("evil"));

            JsonProcessingException jpe = new JsonProcessingException("x") {};
            assertThatCode(() -> JacksonExceptionUtils.buildDiagnosticMetadata(jpe, evil))
                    .doesNotThrowAnyException();
        }
    }

    private static JsonProcessingException parseInvalidJson(String input) {
        try {
            new ObjectMapper().readTree(input);
            throw new IllegalStateException("Expected JsonProcessingException for input: " + input);
        } catch (JsonProcessingException e) {
            return e;
        }
    }

    private static InvalidDefinitionException invalidDefinition() {
        JavaType type = TypeFactory.defaultInstance().constructType(String.class);
        return InvalidDefinitionException.from(
                (JsonParser) null,
                "configuration error",
                type);
    }

    /**
     * Создаёт {@link UnrecognizedPropertyException} с реальным {@link JsonParser}:
     * статический фабричный метод {@code from(JsonParser, ...)} обращается к
     * {@code parser.currentLocation()} и падает с NPE при null-parser.
     */
    private static UnrecognizedPropertyException unrecognizedProperty(String unknownName,
                                                                      java.util.Collection<Object> knownIds) {
        try {
            JsonParser parser = new JsonFactory().createParser("{}");
            return UnrecognizedPropertyException.from(parser, Object.class, unknownName, knownIds);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create parser", e);
        }
    }

}
