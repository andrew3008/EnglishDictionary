package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.micrometer.observation.annotation.Observed;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.annotation.Traced;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Self-test для {@link TracingArchRules}: правило сначала валится на синтетических нарушителях
 * (включая meta-annotation case W7), затем не срабатывает на корректных классах.
 */
class TracingArchRulesTest {

    @Test
    void allowsTracedOnly() {
        JavaClasses classes = new ClassFileImporter().importClasses(GoodTracedOnly.class);

        TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes);
        assertThat(classes).isNotEmpty();
    }

    @Test
    void allowsObservedOnly() {
        JavaClasses classes = new ClassFileImporter().importClasses(GoodObservedOnly.class);

        TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes);
    }

    @Test
    void detectsBothAnnotationsOnSameMethod() {
        JavaClasses classes = new ClassFileImporter().importClasses(BadBothAnnotations.class);

        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("@Observed");
    }

    /**
     * W7: правило учитывает meta-аннотации. Кастомная {@code @CustomObserved}, помеченная
     * {@code @Observed}, считается эквивалентной для целей правила.
     */
    @Test
    void detectsTracedPlusMetaAnnotatedObserved() {
        JavaClasses classes = new ClassFileImporter().importClasses(BadTracedPlusMetaObserved.class);

        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes))
                .hasMessageContaining("@Traced");
    }

    @SuppressWarnings("unused")
    static class GoodTracedOnly {
        @Traced("good.traced")
        void run() {
        }
    }

    @SuppressWarnings("unused")
    static class GoodObservedOnly {
        @Observed(name = "good.observed")
        void run() {
        }
    }

    @SuppressWarnings("unused")
    static class BadBothAnnotations {
        @Traced("bad.both")
        @Observed(name = "bad.both")
        void run() {
        }
    }

    /** Кастомная meta-аннотация: {@code @CustomObserved} → {@code @Observed}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Observed(name = "custom-observed-meta")
    @interface CustomObserved {
    }

    @SuppressWarnings("unused")
    static class BadTracedPlusMetaObserved {
        @Traced("bad.meta")
        @CustomObserved
        void run() {
        }
    }
}
