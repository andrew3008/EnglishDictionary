package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;

public final class OwnerValuesLayout<S> {

    private final CompositeType type;
    private final OwnerHeader header;
    private final List<Field<S, ?>> fields;

    private OwnerValuesLayout(CompositeType type, OwnerHeader header, List<Field<S, ?>> fields) {
        this.type = Objects.requireNonNull(type, "type");
        this.header = Objects.requireNonNull(header, "header");
        this.fields = List.copyOf(fields);
    }

    public static <S> OwnerValuesLayout<S> of(CompositeType type, OwnerHeader header, List<Field<S, ?>> fields) {
        return new OwnerValuesLayout<>(type, header, fields);
    }

    public <O> CompositeData encode(OwnerRegistrationState<O> state, Function<O, S> source) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (state instanceof OwnerRegistrationState.Present<O>(O owner, long generation)) {
            S captured = Objects.requireNonNull(source.apply(owner), "source");
            header.putInto(values, true, generation);
            for (Field<S, ?> field : fields) {
                values.put(field.name(), field.read(captured));
            }
        } else {
            header.putInto(values, false, state.generation());
            for (Field<S, ?> field : fields) {
                values.put(field.name(), field.absentValue());
            }
        }

        return JmxOpenTypeSupport.compositeData(type, values);
    }

    public record OwnerHeader(String presentField, String generationField) {

        public OwnerHeader {
            Objects.requireNonNull(presentField, "presentField");
            Objects.requireNonNull(generationField, "generationField");
        }

        private void putInto(Map<String, Object> values, boolean present, long generation) {
            values.put(presentField, present);
            values.put(generationField, generation);
        }
    }

    public record Field<S, T>(String name, Function<S, T> reader, T absentValue) {

        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(reader, "reader");
            Objects.requireNonNull(absentValue, "absentValue");
        }

        public static <S> Field<S, Long> ofLong(String name, ToLongFunction<S> reader) {
            Objects.requireNonNull(reader, "reader");
            return new Field<>(name, reader::applyAsLong, 0L);
        }

        public static <S> Field<S, Integer> ofInt(String name, ToIntFunction<S> reader) {
            Objects.requireNonNull(reader, "reader");
            return new Field<>(name, reader::applyAsInt, 0);
        }

        public static <S> Field<S, String> ofString(String name, Function<S, String> reader) {
            return new Field<>(name, reader, "");
        }

        private T read(S source) {
            return Objects.requireNonNull(reader.apply(source), name);
        }
    }
}
