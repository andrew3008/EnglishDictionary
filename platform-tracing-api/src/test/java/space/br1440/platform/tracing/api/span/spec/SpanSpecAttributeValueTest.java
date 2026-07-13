package space.br1440.platform.tracing.api.span.spec;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanSpecAttributeValueTest {

    @Test
    void scalarValues_areAccepted() {
        assertThat(SpanSpecAttributeValue.of("x")).isInstanceOf(SpanSpecAttributeValue.StringValue.class);
        assertThat(SpanSpecAttributeValue.of(1L)).isInstanceOf(SpanSpecAttributeValue.LongValue.class);
        assertThat(SpanSpecAttributeValue.of(1.5d)).isInstanceOf(SpanSpecAttributeValue.DoubleValue.class);
        assertThat(SpanSpecAttributeValue.of(true)).isInstanceOf(SpanSpecAttributeValue.BooleanValue.class);
    }

    @Test
    void listValues_areAccepted() {
        assertThat(SpanSpecAttributeValue.stringList(List.of("a"))).isInstanceOf(SpanSpecAttributeValue.StringListValue.class);
        assertThat(SpanSpecAttributeValue.longList(List.of(1L))).isInstanceOf(SpanSpecAttributeValue.LongListValue.class);
        assertThat(SpanSpecAttributeValue.doubleList(List.of(1.0))).isInstanceOf(SpanSpecAttributeValue.DoubleListValue.class);
        assertThat(SpanSpecAttributeValue.booleanList(List.of(true))).isInstanceOf(SpanSpecAttributeValue.BooleanListValue.class);
    }

    @Test
    void nullScalar_isRejected() {
        assertThatThrownBy(() -> SpanSpecAttributeValue.of((String) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullList_isRejected() {
        assertThatThrownBy(() -> SpanSpecAttributeValue.stringList(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullListElement_isRejected() {
        List<String> mutable = new ArrayList<>();
        mutable.add(null);
        assertThatThrownBy(() -> SpanSpecAttributeValue.stringList(mutable))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void listExposure_isDefensiveCopy() {
        List<String> source = new ArrayList<>(List.of("a"));
        SpanSpecAttributeValue.StringListValue value =
                (SpanSpecAttributeValue.StringListValue) SpanSpecAttributeValue.stringList(source);

        source.add("b");

        assertThat(value.values()).containsExactly("a");
    }

    @Test
    void returnedList_isImmutable() {
        SpanSpecAttributeValue.StringListValue value =
                (SpanSpecAttributeValue.StringListValue) SpanSpecAttributeValue.stringList(List.of("a"));

        assertThatThrownBy(() -> value.values().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void arbitraryObjectFactory_isAbsent() throws Exception {
        for (Method method : SpanSpecAttributeValue.class.getDeclaredMethods()) {
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
