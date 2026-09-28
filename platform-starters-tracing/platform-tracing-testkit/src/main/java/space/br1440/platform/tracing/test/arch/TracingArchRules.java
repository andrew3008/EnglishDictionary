package space.br1440.platform.tracing.test.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracingArchRules {

    private static final String TRACED_FQCN = "space.br1440.platform.tracing.api.annotation.Traced";
    private static final String TRACED_ATTRIBUTE_FQCN = "space.br1440.platform.tracing.api.annotation.TracedAttribute";
    private static final String OBSERVED_FQCN = "io.micrometer.observation.annotation.Observed";
    private static final String ASYNC_FQCN = "org.springframework.scheduling.annotation.Async";
    private static final String FUTURE_FQCN = "java.util.concurrent.Future";
    private static final String COMPLETION_STAGE_FQCN = "java.util.concurrent.CompletionStage";
    private static final String MONO_FQCN = "reactor.core.publisher.Mono";
    private static final String FLUX_FQCN = "reactor.core.publisher.Flux";

    private static final Set<String> ALLOWED_BOXED_TYPES = Set.of(
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Character",
            "java.lang.String"
    );

    public static final ArchRule NO_TRACED_AND_OBSERVED_ON_SAME_METHOD = methods()
            .that(haveEffectiveTracedAndObserved())
            .should(neverExist())
            .because("""
                    @Traced and @Observed each create a span — simultaneous use causes \
                    double instrumentation. Use only one annotation; typically @Traced for business \
                    methods and @Observed for Micrometer Observation handler integration.""")
            .allowEmptyShould(true);

    public static final ArchRule NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD = methods()
            .that(haveEffectiveTraced())
            .should(notBeAsyncOrReactive())
            .because("""
                    Synchronous AOP lifecycle of @Traced ends the span on return; async/reactive \
                    return types and @Async yield incorrect duration and lose \
                    completeExceptionally/cancel. Use the fluent API.""")
            .allowEmptyShould(true);

    public static final ArchRule TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST = methods()
            .that(haveTracedAttributeParameter())
            .should(haveOnlyAllowedTracedAttributeParameterTypes())
            .because("""
                    @TracedAttribute accepts only scalars and enums: String.valueOf on an \
                    arbitrary object inflates the span and increases PII risk.""")
            .allowEmptyShould(true);

    private static DescribedPredicate<JavaMethod> haveEffectiveTraced() {
        return new DescribedPredicate<>("have effective @Traced on method or declaring class") {

            @Override
            public boolean test(JavaMethod method) {
                return hasEffectiveTraced(method);
            }
        };
    }

    private static DescribedPredicate<JavaMethod> haveEffectiveTracedAndObserved() {
        return new DescribedPredicate<>("are effective @Traced and annotated with @Observed (incl. meta-annotations)") {

            @Override
            public boolean test(JavaMethod method) {
                boolean hasObserved = method.isAnnotatedWith(OBSERVED_FQCN)
                        || method.isMetaAnnotatedWith(OBSERVED_FQCN);

                return hasEffectiveTraced(method) && hasObserved;
            }
        };
    }

    private static DescribedPredicate<JavaMethod> haveTracedAttributeParameter() {
        return new DescribedPredicate<>("have a parameter annotated with @TracedAttribute") {

            @Override
            public boolean test(JavaMethod method) {
                for (JavaParameter parameter : method.getParameters()) {
                    if (parameter.isAnnotatedWith(TRACED_ATTRIBUTE_FQCN)) {
                        return true;
                    }
                }

                return false;
            }
        };
    }

    private static boolean hasEffectiveTraced(JavaMethod method) {
        return method.isAnnotatedWith(TRACED_FQCN)
                || method.getOwner().isAnnotatedWith(TRACED_FQCN);
    }

    private static boolean hasEffectiveAsync(JavaMethod method) {
        return method.isAnnotatedWith(ASYNC_FQCN)
                || method.getOwner().isAnnotatedWith(ASYNC_FQCN);
    }

    private static ArchCondition<JavaMethod> neverExist() {
        return new ArchCondition<>("never exist (rule violation)") {

            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                events.add(SimpleConditionEvent.violated(method, """
                        Method %s is marked with effective @Traced and @Observed at the same time \
                        — double instrumentation. Keep only one of the annotations.\
                        """.formatted(method.getFullName())));
            }
        };
    }

    private static ArchCondition<JavaMethod> notBeAsyncOrReactive() {
        return new ArchCondition<>("not return async/reactive types and not be @Async") {

            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                JavaClass returnType = method.getRawReturnType();
                if (isAssignableToFqcn(returnType, FUTURE_FQCN)
                        || isAssignableToFqcn(returnType, COMPLETION_STAGE_FQCN)
                        || isAssignableToFqcn(returnType, MONO_FQCN)
                        || isAssignableToFqcn(returnType, FLUX_FQCN)) {
                    events.add(SimpleConditionEvent.violated(method, """
                            Method %s has effective @Traced and returns async/reactive type %s\
                            """.formatted(method.getFullName(), returnType.getName())));
                }

                if (hasEffectiveAsync(method)) {
                    events.add(SimpleConditionEvent.violated(method, """
                            Method %s has effective @Traced and effective @Async\
                            """.formatted(method.getFullName())));
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> haveOnlyAllowedTracedAttributeParameterTypes() {
        return new ArchCondition<>("have only allowlisted @TracedAttribute parameter types") {

            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaParameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotatedWith(TRACED_ATTRIBUTE_FQCN)) {
                        continue;
                    }

                    JavaClass type = parameter.getRawType();
                    if (!isAllowedTracedAttributeType(type)) {
                        events.add(SimpleConditionEvent.violated(method, """
                                Parameter #%d of method %s is annotated with @TracedAttribute \
                                and has disallowed type %s\
                                """.formatted(parameter.getIndex(), method.getFullName(), type.getName())));
                    }
                }
            }
        };
    }

    private static boolean isAllowedTracedAttributeType(JavaClass type) {
        if (type.isPrimitive()) {
            return true;
        }

        if (ALLOWED_BOXED_TYPES.contains(type.getName())) {
            return true;
        }

        return type.isEnum();
    }

    private static boolean isAssignableToFqcn(JavaClass type, String fqcn) {
        if (fqcn.equals(type.getName())) {
            return true;
        }

        try {
            return type.isAssignableTo(fqcn);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
