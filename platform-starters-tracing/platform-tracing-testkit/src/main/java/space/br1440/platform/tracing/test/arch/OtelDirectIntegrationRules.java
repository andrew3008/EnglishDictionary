package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import lombok.experimental.UtilityClass;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Архитектурные guardrails: платформа использует OpenTelemetry SDK/SPI напрямую,
 * не копирует классы SDK и не создаёт локальные аналоги OTel-контрактов (Rules 1–3).
 * <p>
 * <b>Import scope:</b> тесты должны импортировать только {@code space.br1440.platform.tracing}
 * через {@code @AnalyzeClasses(packages = "...")} с {@code ImportOption.DoNotIncludeTests} —
 * не весь classpath с JAR OpenTelemetry, иначе Rule 2 может дать false positive.
 * <p>
 */
@UtilityClass
public final class OtelDirectIntegrationRules {

    private static final Set<String> FORBIDDEN_OTEL_SDK_SIMPLE_NAMES = Set.of(
            "BatchSpanProcessor",
            "SimpleSpanProcessor",
            "SpanLimits",
            "SdkTracerProvider",
            "SdkTracerProviderBuilder",
            "BatchSpanProcessorBuilder",
            "OtlpHttpSpanExporter",
            "OtlpGrpcSpanExporter",
            "W3CTraceContextPropagator",
            "W3CBaggagePropagator"
    );

    private static final Set<String> FORBIDDEN_LOCAL_OTEL_CONTRACT_NAMES = Set.of(
            "SpanProcessor",
            "Sampler",
            "ResourceProvider",
            "SpanExporter",
            "TextMapPropagator"
    );

    /**
     * Rule 1: в platform namespace не должно быть классов с именами core-классов OTel SDK.
     */
    public static final ArchRule NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES = classes()
            .that().resideInAPackage("space.br1440.platform.tracing..")
            .and(haveSimpleNameIn(FORBIDDEN_OTEL_SDK_SIMPLE_NAMES))
            .should(neverExist())
            .allowEmptyShould(true)
            .because("a local copy of an OpenTelemetry SDK class creates fork/merge debt; "
                    + "use the official types from opentelemetry-sdk as a dependency");

    /**
     * Rule 2: в platform namespace не должно быть под-пакетов, имитирующих {@code io.opentelemetry}.
     */
    public static final ArchRule NO_FAKE_OTEL_PACKAGES = classes()
            .that().resideInAPackage("space.br1440..")
            .and(resideInFakeOtelSubPackage())
            .should(neverExist())
            .allowEmptyShould(true)
            .because("an io.opentelemetry sub-package under space.br1440 mimics the upstream namespace");

    /**
     * Rule 3: запрет локальных интерфейсов/классов с точными именами OTel-контрактов (только main).
     */
    public static final ArchRule NO_LOCAL_OTEL_CONTRACT_NAMES = classes()
            .that().resideInAPackage("space.br1440.platform.tracing..")
            .and().resideOutsideOfPackage("..test..")
            .and().resideOutsideOfPackage("..tests..")
            .and(haveSimpleNameIn(FORBIDDEN_LOCAL_OTEL_CONTRACT_NAMES))
            .should(neverExist())
            .allowEmptyShould(true)
            .because("a local contract named after an OTel SPI creates a proprietary tracing runtime; "
                    + "implement io.opentelemetry.sdk.* interfaces");

    /** FQN OTel-интерфейса {@code Span}, на котором объявлен {@code recordException}. */
    private static final String OTEL_SPAN_FQN = "io.opentelemetry.api.trace.Span";

    /**
     * Rule 5: запрет вызова raw {@code Span.recordException(..)} где-либо в platform-namespace.
     * <p>
     * Единственная легальная точка записи исключения — {@code ExceptionRecorder}, который строит
     * exception-event вручную (санитизированно) и сам {@code recordException} НЕ вызывает: events не
     * скрабятся {@code ScrubbingSpanProcessor}'ом, поэтому raw-вызов утёк бы {@code exception.message}/
     * {@code exception.stacktrace} мимо скрабинга. Правило абсолютное (без исключений): после wiring
     * через {@code ExceptionRecorder} ни одного raw-вызова в коде остаться не должно.
     */
    public static final ArchRule NO_RAW_RECORD_EXCEPTION_OUTSIDE_RECORDER = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing..")
            .should().callMethodWhere(isOtelSpanRecordException())
            .because("raw Span.recordException(t) writes an unsanitized exception event "
                    + "(exception.message/stacktrace bypass ScrubbingSpanProcessor); use "
                    + "ExceptionRecorder — see ADR-collector-boundary and Wave A plan");

    private static DescribedPredicate<JavaMethodCall> isOtelSpanRecordException() {
        return new DescribedPredicate<>("call to io.opentelemetry.api.trace.Span.recordException(..)") {
            @Override
            public boolean test(JavaMethodCall call) {
                return "recordException".equals(call.getTarget().getName())
                        && OTEL_SPAN_FQN.equals(call.getTargetOwner().getName());
            }
        };
    }

    private static DescribedPredicate<JavaClass> haveSimpleNameIn(Set<String> forbiddenNames) {
        return new DescribedPredicate<>("have forbidden simple name " + forbiddenNames) {
            @Override
            public boolean test(JavaClass javaClass) {
                return forbiddenNames.contains(javaClass.getSimpleName());
            }
        };
    }

    private static DescribedPredicate<JavaClass> resideInFakeOtelSubPackage() {
        return new DescribedPredicate<>("reside in fake io.opentelemetry sub-package under space.br1440") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getPackageName().contains(".io.opentelemetry.");
            }
        };
    }

    private static ArchCondition<JavaClass> neverExist() {
        return new ArchCondition<>("never exist (rule violation)") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                events.add(SimpleConditionEvent.violated(item,
                        "Class " + item.getName() + " violates the direct OTel integration guardrail — "
                                + "see ADR-otel-direct-integration and otel-compatibility-matrix"));
            }
        };
    }
}
