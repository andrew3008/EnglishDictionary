package space.br1440.platform.tracing.api.span.spec;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanAttributeValueTest {

    @Test
    void scalarValues_areAccepted() {
        assertThat(SpanAttributeValue.of("x")).isInstanceOf(SpanAttributeValue.StringValue.class);
        assertThat(SpanAttributeValue.of(1L)).isInstanceOf(SpanAttributeValue.LongValue.class);
        assertThat(SpanAttributeValue.of(1.5d)).isInstanceOf(SpanAttributeValue.DoubleValue.class);
        assertThat(SpanAttributeValue.of(true)).isInstanceOf(SpanAttributeValue.BooleanValue.class);
    }

    @Test
    void listValues_areAccepted() {
        assertThat(SpanAttributeValue.stringList(List.of("a"))).isInstanceOf(SpanAttributeValue.StringListValue.class);
        assertThat(SpanAttributeValue.longList(List.of(1L))).isInstanceOf(SpanAttributeValue.LongListValue.class);
        assertThat(SpanAttributeValue.doubleList(List.of(1.0))).isInstanceOf(SpanAttributeValue.DoubleListValue.class);
        assertThat(SpanAttributeValue.booleanList(List.of(true))).isInstanceOf(SpanAttributeValue.BooleanListValue.class);
    }

    @Test
    void nullScalar_isRejected() {
        assertThatThrownBy(() -> SpanAttributeValue.of((String) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullList_isRejected() {
        assertThatThrownBy(() -> SpanAttributeValue.stringList(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullListElement_isRejected() {
        List<String> mutable = new ArrayList<>();
        mutable.add(null);
        assertThatThrownBy(() -> SpanAttributeValue.stringList(mutable))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void listExposure_isDefensiveCopy() {
        List<String> source = new ArrayList<>(List.of("a"));
        SpanAttributeValue.StringListValue value =
                (SpanAttributeValue.StringListValue) SpanAttributeValue.stringList(source);

        source.add("b");

        assertThat(value.values()).containsExactly("a");
    }

    @Test
    void returnedList_isImmutable() {
        SpanAttributeValue.StringListValue value =
                (SpanAttributeValue.StringListValue) SpanAttributeValue.stringList(List.of("a"));

        assertThatThrownBy(() -> value.values().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void arbitraryObjectFactory_isAbsent() throws Exception {
        for (Method method : SpanAttributeValue.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertThat(parameterType)
                        .as("factory method %s", method.getName())
                        .isNotEqualTo(Object.class);
            }
        }
    }

    @Test
    void attributeStringObjectOverload_isAbsentOnSpanSpecBuilder() throws Exception {
        for (Method method : SpanSpecBuilder.class.getDeclaredMethods()) {
            if (!"attribute".equals(method.getName())) {
                continue;
            }
            assertThat(method.getParameterTypes())
                    .as("SpanSpecBuilder.attribute overloads")
                    .doesNotContain(Object.class);
        }
    }
}
