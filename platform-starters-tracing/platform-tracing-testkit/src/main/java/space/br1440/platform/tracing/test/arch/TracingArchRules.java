package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import lombok.experimental.UtilityClass;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Платформенные правила архитектурной согласованности кода трассировки.
 * <p>
 * Применяются в test-classpath внутренних tracing-модулей через {@link ArchRule#check}.
 * Импорт классов выполняется через {@code ClassFileImporter} (стандартный паттерн ArchUnit).
 * Подключение {@code platform-tracing-testkit} само по себе код модуля не сканирует — модуль обязан
 * явно импортировать нужные правила в собственный ArchTest. Test-kit не публикуется в Nexus.
 *
 * <h2>Правила</h2>
 * <ul>
 *   <li>{@link #NO_TRACED_AND_OBSERVED_ON_SAME_METHOD} — запрет одновременной разметки
 *       effective {@code @Traced} и Micrometer {@code @Observed}; {@code @Observed} учитывает
 *       meta-аннотации, {@code @Traced} — только прямую разметку метода или declaring class.</li>
 *   <li>{@link #NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD} — запрет effective {@code @Traced} на
 *       async/reactive возвращаемых типах и {@code @Async}.</li>
 *   <li>{@link #TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST} — ограничение типов параметров
 *       с прямой {@code @TracedAttribute}.</li>
 * </ul>
 * <p>
 * Slice 1B: правило {@code ESCAPE_HATCH_BUILDERS_REQUIRE_SUPPRESSION} для удалённых v1
 * {@code Facade*SpanBuilder} factory-методов снято; transport governance принадлежит
 * {@code spans().transport()} и конфигурации агента, а не annotation ABI.
 *
 * <h2>Ограничения, осознанно вне scope</h2>
 * Rule «{@code span handle} обязан использоваться через try-with-resources» не реализуется через
 * ArchUnit: bytecode try-with-resources и ручного {@code try/finally} идентичен.
 */
@UtilityClass
public final class TracingArchRules {

    private static final String TRACED_FQCN = "space.br1440.platform.tracing.api.annotation.Traced";
    private static final String TRACED_ATTRIBUTE_FQCN =
            "space.br1440.platform.tracing.api.annotation.TracedAttribute";
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

    /**
     * Запрещает одновременное применение effective {@code @Traced} и {@code @Observed}.
     * <p>
     * Effective {@code @Traced}: прямая аннотация на методе или на declaring class.
     * {@code @Observed} учитывает meta-аннотации.
     */
    public static final ArchRule NO_TRACED_AND_OBSERVED_ON_SAME_METHOD = methods()
            .that(haveEffectiveTracedAndObserved())
            .should(neverExist(
                    "помечен effective @Traced и @Observed одновременно — double instrumentation. "
                            + "Оставьте только одну из аннотаций."))
            .because("@Traced и @Observed создают span каждый — одновременная разметка приводит "
                    + "к двойной инструментации (double instrumentation). Используйте только одну "
                    + "из аннотаций; обычно — @Traced для бизнес-методов и @Observed для интеграции "
                    + "с Micrometer Observation handlers.")
            .allowEmptyShould(true);

    /**
     * Запрещает effective {@code @Traced} на async/reactive методах.
     * <p>
     * Синхронный AOP-lifecycle закрывает span при возврате метода; для
     * {@code Future}/{@code CompletionStage}/{@code Mono}/{@code Flux} и {@code @Async}
     * длительность и exception status становятся неверными.
     * <p>
     * Reactor-типы проверяются assignability по строковому FQCN без добавления Reactor в
     * compile classpath этого модуля. Разрешение иерархии подтипов зависит от classpath
     * анализируемого модуля: без Reactor на classpath правило всё равно ловит точные
     * {@code Mono}/{@code Flux} по имени raw-типа через {@code isAssignableTo}.
     */
    public static final ArchRule NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD = methods()
            .that(haveEffectiveTraced())
            .should(notBeAsyncOrReactive())
            .because("Синхронный AOP-lifecycle @Traced закрывает span при return; async/reactive "
                    + "возвращаемые типы и @Async дают неверную длительность и теряют "
                    + "completeExceptionally/cancel. Используйте fluent API.")
            .allowEmptyShould(true);

    /**
     * Ограничивает типы параметров с прямой {@code @TracedAttribute}.
     * <p>
     * Допустимы примитивы, их обёртки, {@code String} и конкретные enum-типы.
     * Произвольный {@code Number}, domain-объекты, коллекции, массивы и {@code Optional} запрещены.
     */
    public static final ArchRule TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST = methods()
            .that(haveTracedAttributeParameter())
            .should(haveOnlyAllowedTracedAttributeParameterTypes())
            .because("@TracedAttribute принимает только скаляры и enum: String.valueOf на "
                    + "произвольном объекте раздувает span и повышает риск PII.")
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
        return new DescribedPredicate<>(
                "are effective @Traced and annotated with @Observed (incl. meta-annotations)") {
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

    private static ArchCondition<JavaMethod> neverExist(String reasonSuffix) {
        return new ArchCondition<>("never exist (rule violation)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                events.add(SimpleConditionEvent.violated(method,
                        "Метод " + method.getFullName() + " " + reasonSuffix));
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
                    events.add(SimpleConditionEvent.violated(method,
                            "Метод " + method.getFullName()
                                    + " имеет effective @Traced и возвращает async/reactive тип "
                                    + returnType.getName()));
                }
                if (hasEffectiveAsync(method)) {
                    events.add(SimpleConditionEvent.violated(method,
                            "Метод " + method.getFullName()
                                    + " имеет effective @Traced и effective @Async"));
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
                        events.add(SimpleConditionEvent.violated(method,
                                "Параметр #" + parameter.getIndex() + " метода "
                                        + method.getFullName()
                                        + " помечен @TracedAttribute и имеет недопустимый тип "
                                        + type.getName()));
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

    /**
     * Assignability по FQCN. Если тип отсутствует на classpath импорта, ArchUnit сравнивает
     * иерархию доступных классов; точное совпадение имени всё равно покрывается.
     */
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
