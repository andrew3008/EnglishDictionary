package space.br1440.platform.tracing.api.propagation.control;

import java.util.Objects;
import java.util.Optional;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

/**
 * Неизменяемые значения четырёх платформенных заголовков для исходящего carrier.
 * Имя заголовка определяется слотом; пустой слот запрещает передачу этого имени.
 *
 * @param forceTrace    значение {@link PlatformHeaders#X_TRACE_ON}
 * @param qaTrace       значение {@link PlatformHeaders#X_QA_TRACE}
 * @param requestId     значение {@link PlatformHeaders#X_REQUEST_ID}
 * @param correlationId значение {@link PlatformHeaders#X_CORRELATION_ID}
 */
public record OutboundPropagationHeaders(@Nonnull Optional<String> forceTrace,
                                         @Nonnull Optional<String> qaTrace,
                                         @Nonnull Optional<String> requestId,
                                         @Nonnull Optional<String> correlationId) {
    public static final OutboundPropagationHeaders EMPTY = new OutboundPropagationHeaders(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
    );

    public OutboundPropagationHeaders {
        Objects.requireNonNull(forceTrace, "forceTrace").ifPresent(value -> rejectLineBreaks(value, "forceTrace"));
        Objects.requireNonNull(qaTrace, "qaTrace").ifPresent(value -> rejectLineBreaks(value, "qaTrace"));
        Objects.requireNonNull(requestId, "requestId").ifPresent(value -> rejectLineBreaks(value, "requestId"));
        Objects.requireNonNull(correlationId, "correlationId")
                .ifPresent(value -> rejectLineBreaks(value, "correlationId"));
    }

    private static void rejectLineBreaks(String value, String slotName) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(slotName + " must not contain CR/LF");
        }
    }
}
