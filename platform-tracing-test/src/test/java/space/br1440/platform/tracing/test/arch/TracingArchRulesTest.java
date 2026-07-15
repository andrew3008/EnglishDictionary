package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.micrometer.observation.annotation.Observed;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.annotation.Traced;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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

    @Test
    void traceOperationsAndSpanFactoryKeepApprovedApiShape() throws NoSuchMethodException {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("space.br1440.platform.tracing");

        noClasses()
                .that().resideInAPackage("space.br1440.platform.tracing..")
                .should().haveSimpleName("Manual" + "Tracing")
                .check(classes);

        assertThat(classCanBeLoaded("space.br1440.platform.tracing.api.manual." + "Manual" + "Tracing")).isFalse();

        assertThat(TraceOperations.class.getMethod("spans").getReturnType()).isEqualTo(SpanFactory.class);
        assertThat(hasNoArgMethod(TraceOperations.class, "manual")).isFalse();

        assertThat(SpanFactory.class.getMethod("operation", String.class).getReturnType().getSimpleName())
                .isEqualTo("OperationSpanBuilder");
        assertThat(hasMethod(SpanFactory.class, "operation" + "Span", String.class)).isFalse();
        assertThat(SpanFactory.class.getMethod("fromSpec", SpanSpec.class).getReturnType().getSimpleName())
                .isEqualTo("SpanExecution");
        assertThat(hasMethod(SpanFactory.class, "span" + "FromSpec", SpanSpec.class)).isFalse();
    }

    @Test
    void traceparentReaderKeepsApprovedApiShapeAndAccess() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages(
                        "space.br1440.platform.tracing.api",
                        "space.br1440.platform.tracing.core");

        ModuleTaxonomyArchRules.API_PROPAGATION_HAS_NO_PUBLIC_PARSERS.check(classes);
        ModuleTaxonomyArchRules.OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED.check(classes);
        ModuleTaxonomyArchRules.REQUEST_ID_SUPPORT_IMPL_ACCESS_RESTRICTED.check(classes);
        ModuleTaxonomyArchRules.CONTROL_IMPLS_ONLY_IN_CORE.check(classes);
        ModuleTaxonomyArchRules.CONTROL_IMPL_ACCESS_RESTRICTED.check(classes);
        ModuleTaxonomyArchRules.API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL.check(classes);
        ModuleTaxonomyArchRules.API_MDC_CONTRACTS_ONLY.check(classes);
        ModuleTaxonomyArchRules.API_MDC_NO_SLF4J_IMPORTS.check(classes);

        assertThat(classCanBeLoaded("space.br1440.platform.tracing.api.propagation." + "Traceparent" + "Parser")).isFalse();
    }

    private static boolean classCanBeLoaded(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean hasNoArgMethod(Class<?> type, String name) {
        return hasMethod(type, name);
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getMethod(name, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
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
