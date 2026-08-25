package space.br1440.platform.errorhandling.core.util.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.experimental.UtilityClass;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.http.ErrorMediaTypes;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@UtilityClass
public final class UpstreamResponseReader {

    public static final int MAX_UPSTREAM_ERROR_BODY_BYTES = 8192;

    private static final int MAX_ERROR_CODE_LENGTH = 128;
    private static final int MAX_DOMAIN_LENGTH = 128;
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).build())
            .build();

    private static final ObjectMapper MAPPER = JsonMapper.builder(JSON_FACTORY)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    @NonNull
    public static UpstreamOutcome fromFailure(@NonNull Throwable failure) {
        if (failure instanceof RestClientResponseException restEx) {
            MediaType contentType = (restEx.getResponseHeaders() != null)
                    ? restEx.getResponseHeaders().getContentType()
                    : null;
            return read(restEx.getStatusCode().value(), contentType, restEx.getResponseBodyAsByteArray());
        }

        if (failure instanceof ResourceAccessException) {
            return UpstreamOutcome.noResponse();
        }

        if (FeignExceptionUtils.isFeignException(failure)) {
            return fromFeignFailure(failure);
        }

        return UpstreamOutcome.noResponse();
    }

    @NonNull
    private static UpstreamOutcome fromFeignFailure(@NonNull Throwable failure) {
        int status = FeignExceptionUtils.extractFeignStatus(failure);
        if (status <= 0) {
            return UpstreamOutcome.noResponse();
        }

        String[] bodyHolder = {null};
        ReflectionUtils.invokeMethod(failure, "contentUTF8", content -> {
            if (content instanceof String s) {
                bodyHolder[0] = s;
            }
        });

        MediaType[] contentTypeHolder = {null};
        ReflectionUtils.invokeMethod(failure, "responseHeaders", rawHeaders -> {
            if (rawHeaders instanceof Map<?, ?> headers) {
                contentTypeHolder[0] = extractContentType(headers);
            }
        });

        if (StringUtils.isNullOrBlank(bodyHolder[0])) {
            return UpstreamOutcome.ofStatus(status);
        }

        Optional<UpstreamIdentity> identity = recognizeText(bodyHolder[0], contentTypeHolder[0]);
        return identity.map(value -> UpstreamOutcome.ofRecognized(status, value))
                .orElseGet(() -> UpstreamOutcome.ofStatus(status));
    }

    @Nullable
    private static MediaType extractContentType(@NonNull Map<?, ?> headers) {
        for (Map.Entry<?, ?> entry : headers.entrySet()) {
            if (entry.getKey() instanceof String key && "Content-Type".equalsIgnoreCase(key)) {
                Object values = entry.getValue();
                if (values instanceof Collection<?> coll && !coll.isEmpty()) {
                    Object first = coll.iterator().next();
                    if (first instanceof String s && StringUtils.hasText(s)) {
                        try {
                            return MediaType.parseMediaType(s);
                        } catch (IllegalArgumentException ignored) {
                            return null;
                        }
                    }
                }

                return null;
            }
        }

        return null;
    }

    @NonNull
    public static UpstreamOutcome read(int httpStatus, @Nullable MediaType contentType, @Nullable byte[] body) {
        Optional<UpstreamIdentity> identity = recognizeBytes(body, contentType);
        return identity.map(value -> UpstreamOutcome.ofRecognized(httpStatus, value))
                .orElseGet(() -> UpstreamOutcome.ofStatus(httpStatus));
    }

    @NonNull
    private static Optional<UpstreamIdentity> recognizeBytes(@Nullable byte[] body, @Nullable MediaType contentType) {
        if (body == null || body.length == 0 || body.length > MAX_UPSTREAM_ERROR_BODY_BYTES) {
            return Optional.empty();
        }

        if (!isRecognizedContentType(contentType)) {
            return Optional.empty();
        }

        String text = decodeStrictUtf8(body);
        if (text == null) {
            return Optional.empty();
        }

        return recognizeParsedText(text);
    }

    @NonNull
    private static Optional<UpstreamIdentity> recognizeText(@Nullable String body, @Nullable MediaType contentType) {
        if (StringUtils.isNullOrBlank(body) || body.length() > MAX_UPSTREAM_ERROR_BODY_BYTES) {
            return Optional.empty();
        }

        if (!isRecognizedContentType(contentType)) {
            return Optional.empty();
        }

        return recognizeParsedText(body);
    }

    private static boolean isRecognizedContentType(@Nullable MediaType contentType) {
        if (contentType == null) {
            return false;
        }

        if (!contentType.isCompatibleWith(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE)) {
            return false;
        }

        String charset = contentType.getParameter("charset");
        return charset == null ||
                StandardCharsets.UTF_8.name().equalsIgnoreCase(charset) ||
                "UTF8".equalsIgnoreCase(charset);
    }

    @NonNull
    private static Optional<UpstreamIdentity> recognizeParsedText(@NonNull String text) {
        JsonNode root;
        try (JsonParser parser = MAPPER.createParser(text)) {
            root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }

            if (parser.nextToken() != null) {
                return Optional.empty();
            }
        } catch (Exception malformed) {
            return Optional.empty();
        }

        String errorCode = textField(root, "errorCode");
        if (errorCode == null || !isSafeToken(errorCode, MAX_ERROR_CODE_LENGTH)) {
            return Optional.empty();
        }

        String domain = textField(root, "domain");
        if (domain != null && isSafeToken(domain, MAX_DOMAIN_LENGTH)) {
            return Optional.of(UpstreamIdentity.of(errorCode, domain));
        }

        return Optional.of(UpstreamIdentity.of(errorCode));
    }

    @Nullable
    private static String textField(@NonNull JsonNode root, @NonNull String fieldName) {
        JsonNode field = root.get(fieldName);
        return (field != null && field.isTextual()) ? field.asText() : null;
    }

    private static boolean isSafeToken(@Nullable String value, int maxLength) {
        return StringUtils.hasText(value)
                && value.length() <= maxLength
                && SAFE_TOKEN.matcher(value).matches();
    }

    @Nullable
    private static String decodeStrictUtf8(@NonNull byte[] body) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(body));
            return decoded.toString();
        } catch (CharacterCodingException malformedUtf8) {
            return null;
        }
    }
}
