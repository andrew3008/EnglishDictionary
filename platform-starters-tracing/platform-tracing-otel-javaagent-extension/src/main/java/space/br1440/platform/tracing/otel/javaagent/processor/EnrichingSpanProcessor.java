package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.List;
import jakarta.annotation.Nonnull;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ResourceIdentityLogEvent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.StatusData;
import lombok.experimental.UtilityClass;

/**
 * Обогащает span platform-специфичными атрибутами: тип (HTTP/DB/Kafka/internal), результат
 * (success/failure) и, для ошибочных CLIENT-спанов, имя upstream remote-сервиса.
 * Default {@code platform.trace.type} пишется в {@code onEnding}, после validation.
 */
public final class EnrichingSpanProcessor implements PlatformEndingSpanProcessor, ProcessorDiagnosticIdentity {

    private static final RateLimitedLogger log = new RateLimitedLogger(EnrichingSpanProcessor.class);

    private static final String DIAGNOSTIC_ID = JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_ENRICHMENT;
    private static final AttributeKey<String> PLATFORM_TYPE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE);
    private static final AttributeKey<String> PLATFORM_RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);
    private static final AttributeKey<String> PLATFORM_REMOTE_SERVICE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REMOTE_SERVICE);

    /**
     * Ключи DB semantic conventions для {@link #hasDbSystemAttribute(ReadableSpan)}.
     * <ul>
     *   <li>{@code db.system.name} — стабильная semconv >= 1.28 (проверяется первой).</li>
     *   <li>{@code db.system} — legacy semconv <= 1.27; эмитится Agent 2.28.x без
     *       {@code otel.semconv-stability.opt-in=database}. Также может сосуществовать с
     *       {@code db.system.name} в режиме dual-emission ({@code database/dup}).
     *       Удалять только после platform-wide миграции на стабильную DB semconv.</li>
     * </ul>
     */
    @UtilityClass
    private static final class DbSemanticAttributeKeys {
        static final AttributeKey<String> SYSTEM_NAME = AttributeKey.stringKey("db.system.name");

        static final AttributeKey<String> LEGACY_SYSTEM = AttributeKey.stringKey("db.system");
    }

    private static final List<AttributeKey<String>> REMOTE_SERVICE_PRIORITY = List.of(
            AttributeKey.stringKey("peer.service"),
            AttributeKey.stringKey("rpc.service"),
            AttributeKey.stringKey("server.address")
    );

    public EnrichingSpanProcessor() {
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        // type не пишется здесь: ValidatingSpanProcessor должен увидеть исходный null
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        StatusData status = span.toSpanData().getStatus();

        applyDefaultType(span);
        reclassifyAsDatabaseIfNeeded(span);
        applyDefaultResult(span, status);
        recordRemoteServiceOnError(span, status);
    }

    private static void applyDefaultType(ReadWriteSpan span) {
        if (span.getAttribute(PLATFORM_TYPE_KEY) == null) {
            span.setAttribute(PLATFORM_TYPE_KEY, defaultTypeFor(span.getKind()));
        }
    }

    private void applyDefaultResult(ReadWriteSpan span, StatusData status) {
        if (span.getAttribute(PLATFORM_RESULT_KEY) == null) {
            span.setAttribute(PLATFORM_RESULT_KEY, defaultResultFor(status).value());
        }
    }

    private void reclassifyAsDatabaseIfNeeded(ReadWriteSpan span) {
        if (span.getKind() != SpanKind.CLIENT || !hasDbSystemAttribute(span)) {
            return;
        }

        String currentType = span.getAttribute(PLATFORM_TYPE_KEY);
        if (currentType == null || SpanCategory.HTTP_CLIENT.value().equals(currentType)) {
            span.setAttribute(PLATFORM_TYPE_KEY, SpanCategory.DATABASE.value());
        }
    }

    private void recordRemoteServiceOnError(ReadWriteSpan span, StatusData status) {
        boolean isErrorClient = (span.getKind() == SpanKind.CLIENT)
                && status != null
                && (status.getStatusCode() == StatusCode.ERROR)
                && (span.getAttribute(PLATFORM_REMOTE_SERVICE_KEY) == null);
        if (!isErrorClient) {
            return;
        }

        String remoteService = extractRemoteService(span);
        if (remoteService == null) {
            return;
        }

        span.setAttribute(PLATFORM_REMOTE_SERVICE_KEY, remoteService);
    }

    private static boolean hasDbSystemAttribute(ReadableSpan span) {
        return span.getAttribute(DbSemanticAttributeKeys.SYSTEM_NAME) != null
                || span.getAttribute(DbSemanticAttributeKeys.LEGACY_SYSTEM) != null;
    }

    @Override
    public boolean isOnEndingRequired() {
        return true;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    private static String defaultTypeFor(SpanKind kind) {
        return switch (kind) {
            case SERVER -> SpanCategory.HTTP_SERVER.value();
            case CLIENT -> SpanCategory.HTTP_CLIENT.value();
            case PRODUCER -> SpanCategory.KAFKA_PRODUCER.value();
            case CONSUMER -> SpanCategory.KAFKA_CONSUMER.value();
            case INTERNAL -> SpanCategory.INTERNAL.value();
        };
    }

    private static SpanResult defaultResultFor(StatusData status) {
        if (status == null) {
            return SpanResult.SUCCESS;
        }

        return (status.getStatusCode() == StatusCode.ERROR) ? SpanResult.FAILURE : SpanResult.SUCCESS;
    }

    private String extractRemoteService(ReadableSpan span) {
        try {
            for (AttributeKey<String> key : REMOTE_SERVICE_PRIORITY) {
                String value = span.getAttribute(key);
                if (StringUtils.isNullOrBlank(value)) {
                    continue;
                }

                if ("server.address".equals(key.getKey()) && looksLikeIpAddress(value)) {
                    continue;
                }

                return value;
            }
        } catch (RuntimeException e) {
            log.log(ResourceIdentityLogEvent.UPSTREAM_SERVICE_EXTRACTION_FAILED);
        }

        return null;
    }

    private static boolean looksLikeIpAddress(String value) {
        if (value.indexOf(':') >= 0) {
            return isHexAndColonsOnly(value);
        }

        int dots = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }

        return dots == 3;
    }

    private static boolean isHexAndColonsOnly(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') {
                continue;
            }

            boolean digit = c >= '0' && c <= '9';
            boolean lowerHex = c >= 'a' && c <= 'f';
            boolean upperHex = c >= 'A' && c <= 'F';
            if (!digit && !lowerHex && !upperHex) {
                return false;
            }
        }

        return true;
    }
}
