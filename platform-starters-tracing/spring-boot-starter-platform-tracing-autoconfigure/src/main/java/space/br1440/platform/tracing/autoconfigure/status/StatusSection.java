package space.br1440.platform.tracing.autoconfigure.status;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

final class StatusSection<T> {

    private final T source;
    private final Map<String, Object> fields = new LinkedHashMap<>();

    private StatusSection(T source) {
        this.source = source;
    }

    static <T> StatusSection<T> from(T source) {
        return new StatusSection<>(source);
    }

    static StatusSection<Void> create() {
        return new StatusSection<>(null);
    }

    StatusSection<T> field(String name, Function<? super T, ?> getter) {
        fields.put(name, source == null ? null : getter.apply(source));
        return this;
    }

    StatusSection<T> value(String name, Object value) {
        fields.put(name, value);
        return this;
    }

    Map<String, Object> build() {
        return Collections.unmodifiableMap(fields);
    }
}
