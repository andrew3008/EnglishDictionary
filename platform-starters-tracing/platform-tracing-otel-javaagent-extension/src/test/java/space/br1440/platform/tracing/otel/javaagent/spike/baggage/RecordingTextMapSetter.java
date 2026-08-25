package space.br1440.platform.tracing.otel.javaagent.spike.baggage;

import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.ArrayList;
import java.util.List;

/**
 * Записывает все {@code setter.set(carrier, key, value)} для анализа порядка inject в spike.
 */
public final class RecordingTextMapSetter<C> implements TextMapSetter<C> {

    public record SetCall(String key, String value) {}

    private final TextMapSetter<C> delegate;
    private final List<SetCall> calls = new ArrayList<>();

    public RecordingTextMapSetter(TextMapSetter<C> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void set(C carrier, String key, String value) {
        calls.add(new SetCall(key, value));
        delegate.set(carrier, key, value);
    }

    public List<SetCall> calls() {
        return List.copyOf(calls);
    }

    public void clear() {
        calls.clear();
    }
}
