package space.br1440.platform.errorhandling.utils;

import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Билдер для {@link ErrorInfoDetail#getClientMetadata() clientMetadata} -- структурированных метаданных,
 * безопасных для передачи клиенту в DTO.
 *
 * <h2>Контракт</h2>
 * <ul>
 *   <li>Ключи: {@code lowerCamelCase}, max 64 символа, стабильны между релизами (AIP-193).</li>
 *   <li>Значения: безопасны для передачи клиенту, без sensitive data.</li>
 *   <li>Для SRE-данных (vendor codes, stack hints, remote host) использовать {@link DiagnosticMetadataBuilder}.</li>
 * </ul>
 *
 * <h2>Семантика {@code put}</h2>
 * <ul>
 *   <li><b>Skip-on-null</b>: {@code put(key, null)} не порождает запись. В structured-logging
 *       отсутствие ключа не равно ключу со значением {@code "null"}.</li>
 *   <li><b>Skip-on-empty</b> для коллекций: {@code put(key, List.of())} не порождает запись.
 *       Пустая строка в JSON воспринимается потребителем как "данные есть, но пустые" -- хуже,
 *       чем отсутствие ключа.</li>
 *   <li>Значения <b>не оборачиваются</b> в кавычки -- потребитель JSON-контракта видит голую строку.</li>
 * </ul>
 *
 * <h2>Порядок ключей</h2>
 * <p>Билдер сохраняет порядок вставки ({@link LinkedHashMap}). Это часть контракта для
 * snapshot-тестов и читаемости логов. <b>Не заменять</b> на {@code com.google.common.collect.ImmutableMap}:
 * Guava-{@code ImmutableMap} использует хеш-таблицу, и порядок -- detail реализации.
 */
public final class ClientMetadataBuilder {

    private final Map<String, String> entries = new LinkedHashMap<>();

    private ClientMetadataBuilder() {
    }

    public static ClientMetadataBuilder builder() {
        return new ClientMetadataBuilder();
    }

    public ClientMetadataBuilder put(String key, String value) {
        if (value != null) {
            entries.put(key, value);
        }

        return this;
    }

    public ClientMetadataBuilder put(String key, Number value) {
        if (value != null) {
            entries.put(key, value.toString());
        }

        return this;
    }

    public ClientMetadataBuilder put(String key, UUID value) {
        if (value != null) {
            entries.put(key, value.toString());
        }

        return this;
    }

    public ClientMetadataBuilder put(String key, Collection<String> values) {
        if (values != null && !values.isEmpty()) {
            entries.put(key, String.join(", ", values));
        }

        return this;
    }

    public Map<String, String> build() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }
}
