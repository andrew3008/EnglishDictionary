package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.mdc.RemoteServiceMdc;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class EnrichingSpanProcessor implements ExtendedSpanProcessor {

    private static final AttributeKey<String> PLATFORM_TYPE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE);
    private static final AttributeKey<String> PLATFORM_RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);
    private static final AttributeKey<String> PLATFORM_REMOTE_SERVICE_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REMOTE_SERVICE);
    private static final AttributeKey<String> PLATFORM_REQUEST_ID_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REQUEST_ID);
    private static final AttributeKey<String> PLATFORM_POLICY_VERSION_KEY = AttributeKey.stringKey("platform.policy.version");

    /**
     * Ключи DB semantic conventions для {@link #hasDbSystemAttribute(ReadableSpan)}.
     * <ul>
     *   <li>{@code db.system.name} — стабильная semconv ≥ 1.28 (проверяется первой).</li>
     *   <li>{@code db.system} — legacy semconv ≤ 1.27; эмитится Agent 2.28.x без
     *       {@code otel.semconv-stability.opt-in=database}. Также может сосуществовать с
     *       {@code db.system.name} в режиме dual-emission ({@code database/dup}).
     *       Удалять только после platform-wide миграции на стабильную DB semconv.</li>
     * </ul>
     */
    private static final class DbSemanticAttributeKeys {
        static final AttributeKey<String> SYSTEM_NAME = AttributeKey.stringKey("db.system.name");

        @Deprecated
        static final AttributeKey<String> LEGACY_SYSTEM = AttributeKey.stringKey("db.system");

        private DbSemanticAttributeKeys() {
        }
    }

    public static final List<String> DEFAULT_REMOTE_SERVICE_PRIORITY = List.of(
            "peer.service", "rpc.service", "server.address"
    );

    private final List<AttributeKey<String>> remoteServicePriority;

    public EnrichingSpanProcessor() {
        this(DEFAULT_REMOTE_SERVICE_PRIORITY);
    }

    public EnrichingSpanProcessor(List<String> remoteServicePriority) {
        List<AttributeKey<String>> keys = new ArrayList<>(remoteServicePriority.size());
        for (String name : remoteServicePriority) {
            if (Strings.isNotBlank(name)) {
                keys.add(AttributeKey.stringKey(name.trim()));
            }
        }

        this.remoteServicePriority = List.copyOf(keys);
    }

    @Override
    public void onStart(@Nonnull Context parentContext, ReadWriteSpan span) {
        if (span.getAttribute(PLATFORM_TYPE_KEY) == null) {
            span.setAttribute(PLATFORM_TYPE_KEY, defaultTypeFor(span.getKind()));
        }

        Baggage baggage = Baggage.fromContext(parentContext);
        String reqId = baggage.getEntryValue(PlatformAttributes.PLATFORM_REQUEST_ID);
        if (reqId != null && !reqId.isEmpty()) {
            span.setAttribute(PLATFORM_REQUEST_ID_KEY, reqId);
        }

        String policyVer = baggage.getEntryValue("platform.policy.version");
        if (policyVer != null && !policyVer.isEmpty()) {
            span.setAttribute(PLATFORM_POLICY_VERSION_KEY, policyVer);
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnding(ReadWriteSpan span) {
        StatusData status = span.toSpanData().getStatus();
        if (span.getAttribute(PLATFORM_RESULT_KEY) == null) {
            span.setAttribute(PLATFORM_RESULT_KEY, defaultResultFor(status).value());
        }

        if (span.getKind() == SpanKind.CLIENT && hasDbSystemAttribute(span)) {
            String currentType = span.getAttribute(PLATFORM_TYPE_KEY);
            if (currentType == null || SpanCategory.HTTP_CLIENT.value().equals(currentType)) {
                span.setAttribute(PLATFORM_TYPE_KEY, SpanCategory.DATABASE.value());
            }
        }

        if (span.getKind() == SpanKind.CLIENT
                && status != null
                && status.getStatusCode() == StatusCode.ERROR
                && span.getAttribute(PLATFORM_REMOTE_SERVICE_KEY) == null) {
            String remoteService = extractRemoteService(span);
            if (remoteService != null) {
                span.setAttribute(PLATFORM_REMOTE_SERVICE_KEY, remoteService);
                RemoteServiceMdc.putIfPresent(remoteService, span.toSpanData().getTraceId());
            }
        }
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

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
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
            for (AttributeKey<String> key : remoteServicePriority) {
                String value = span.getAttribute(key);
                if (Strings.isBlank(value)) {
                    continue;
                }

                if ("server.address".equals(key.getKey()) && looksLikeIpAddress(value)) {
                    continue;
                }

                return value;
            }
        } catch (RuntimeException e) {
            log.debug("Failed to extract upstream service name from CLIENT span: {}", e.getMessage());
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

        return (dots == 3);
    }

    private static boolean isHexAndColonsOnly(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') {
                continue;
            }

            boolean digit = (c >= '0' && c <= '9');
            boolean lowerHex = (c >= 'a' && c <= 'f');
            boolean upperHex = (c >= 'A' && c <= 'F');
            if (!digit && !lowerHex && !upperHex) {
                return false;
            }
        }

        return true;
    }
}
