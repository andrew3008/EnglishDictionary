package space.br1440.platform.errorhandling.core.util.correlation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;
import space.br1440.platform.errorhandling.core.util.CorrelationIdGenerator;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.regex.Pattern;

/**
 * Единый резолвер correlation-id для входящего HTTP-трафика по «первому» значению каждого заголовка.
 *
 * <p>Http Servlet и WebFlux ({@code ServerHttpRequest#getHeaders().getFirst}) отличаются только транспортом чтения;
 * вся бизнес-логика, порядок приоритетов и формулировки журналов сосредоточены здесь, чтобы архитектурно
 * исключить рассинхрон MVC ↔ reactive.
 *
 * <h2>Приоритет источников</h2>
 * <ol>
 *   <li>{@code traceparent} (при включённом {@code acceptTraceparent}), см. {@link W3CTraceparentCorrelationExtractor};</li>
 *   <li>заголовки из «хвоста» после traceparent ({@link CorrelationIdFallbackHeaderChain#AFTER_TRACEPARENT});</li>
 *   <li>{@link CorrelationIdGenerator}.</li>
 * </ol>
 */
@Slf4j
public final class CorrelationIdHttpInboundResolver {

    /**
     * Минимальный контракт: для имени заголовка вернуть первое строковое значение или {@code null}/пустое.
     * Реализации — лямбды на servlet / reactive без дублирования алгоритма.
     */
    @FunctionalInterface
    public interface HeaderReader {
        @Nullable
        String getFirst(@NonNull String headerName);
    }

    private final int maxLength;
    private final Pattern allowedPattern;
    private final boolean acceptTraceparent;

    public CorrelationIdHttpInboundResolver(
            @NonNull ErrorHandlingProperties.CorrelationId correlationIdConfig,
            boolean acceptTraceparent) {
        this.maxLength = correlationIdConfig.getMaxLength();
        this.allowedPattern = CorrelationIdAllowedPatternCompilation.compile(correlationIdConfig);
        this.acceptTraceparent = acceptTraceparent;
    }

    @NonNull
    public String resolve(@NonNull HeaderReader reader) {
        if (acceptTraceparent) {
            String traceparentHeader = reader.getFirst(ErrorHandlingHeaders.TRACEPARENT);
            // trim внутри extractor — единая политика для servlet и WebFlux
            if (StringUtils.hasText(traceparentHeader)) {
                String traceId = W3CTraceparentCorrelationExtractor.traceIdOrNull(traceparentHeader, true);
                if (traceId != null) {
                    return traceId;
                }

                log.warn("Invalid or all-zero 'traceparent' header rejected: {}",
                        LogSanitizer.sanitize(traceparentHeader));
            }
        }

        for (String headerName : CorrelationIdFallbackHeaderChain.AFTER_TRACEPARENT) {
            String accepted = assessHeader(reader, headerName);
            if (accepted != null) {
                return accepted;
            }
        }

        return CorrelationIdGenerator.generate();
    }

    @Nullable
    private String assessHeader(@NonNull HeaderReader reader, @NonNull String headerName) {
        String raw = reader.getFirst(headerName);
        if (!StringUtils.hasText(raw)) {
            return null;
        }

        CorrelationIdInboundHeaderAssessment.Outcome outcome = CorrelationIdInboundHeaderAssessment.assess(
                raw,
                maxLength,
                allowedPattern,
                /* HTTP: парсер не отдаёт CR/LF в значении — STOMP идёт отдельным резолвером */
                false);

        CorrelationIdInboundHeaderAssessment.RejectReason reason = outcome.rejectReason();
        if (reason == null) {
            return outcome.acceptedValue();
        }

        switch (reason) {
            case LENGTH -> log.warn("Correlation-id header '{}' exceeds maxLength={} ({} chars), rejected: {}",
                    headerName, maxLength, raw.length(), LogSanitizer.sanitize(raw));
            case CHARSET -> log.warn("Correlation-id header '{}' contains disallowed characters, rejected: {}",
                    headerName, LogSanitizer.sanitize(raw));
            case CRLF_CONTROL -> log.warn(
                    "Correlation-id header '{}' rejected due to control characters (CR/LF/NUL): {}",
                    headerName, LogSanitizer.sanitize(raw));
        }

        return null;
    }
}
