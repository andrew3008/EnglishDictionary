package space.br1440.platform.errorhandling.utils;

import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Билдер для {@link ErrorInfoDetail#getDiagnosticMetadata() diagnosticMetadata} --
 * метаданных для SRE и разработчиков, <b>не передающихся</b> клиенту.
 *
 * <h2>Зачем отдельный класс</h2>
 * <p>Симметричен {@link ClientMetadataBuilder} по API, но семантически отличается целевым
 * контрактом: результат пишется в {@link ErrorInfoDetail#getDiagnosticMetadata()} и
 * <b>не попадает</b> в DTO-ответ клиенту (см. {@link space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper}).
 *
 * <p>Использование двух различных типов вместо одного билдера с флагом &laquo;target=client/diagnostic&raquo;
 * даёт <b>compile-time защиту</b>: разработчик не может случайно положить vendor-коды или stack hint в {@code clientMetadata}.
 */
public final class DiagnosticMetadataBuilder {

    private final Map<String, String> entries = new LinkedHashMap<>();

    private DiagnosticMetadataBuilder() {
    }

    public static DiagnosticMetadataBuilder builder() {
        return new DiagnosticMetadataBuilder();
    }

    public DiagnosticMetadataBuilder put(String key, String value) {
        if (value != null) {
            entries.put(key, value);
        }

        return this;
    }

    public DiagnosticMetadataBuilder put(String key, Number value) {
        if (value != null) {
            entries.put(key, value.toString());
        }

        return this;
    }

    public DiagnosticMetadataBuilder put(String key, UUID value) {
        if (value != null) {
            entries.put(key, value.toString());
        }

        return this;
    }

    public DiagnosticMetadataBuilder put(String key, Collection<String> values) {
        if (values != null && !values.isEmpty()) {
            entries.put(key, String.join(", ", values));
        }

        return this;
    }

    public Map<String, String> build() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }
}
