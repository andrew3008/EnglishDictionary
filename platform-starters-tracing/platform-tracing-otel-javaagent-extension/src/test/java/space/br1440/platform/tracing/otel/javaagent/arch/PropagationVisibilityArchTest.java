package space.br1440.platform.tracing.otel.javaagent.arch;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.otel.javaagent.propagation.PropagationDefaults;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Именованные architecture rules G-PROP-3..G-PROP-8 propagation cleanup (§8.3 плана): минимальная
 * visibility implementation-типов propagation package, отсутствие удалённых posредников и явный
 * allowlist прямых вызовов {@code PlatformPropagationGate}.
 *
 * <p>G-PROP-1/G-PROP-2 остаются в {@link PropagationBoundaryArchTest}.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class PropagationVisibilityArchTest {

    private static final String PROPAGATION_PACKAGE = "space.br1440.platform.tracing.otel.javaagent.propagation..";
    private static final String GATE_FQN =
            "space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate";

    /**
     * G-PROP-3: запрет {@code Class.forName}/declared-field reflection в propagation package как
     * замены visibility narrowing. Единственный допустимый reflection-consumer шести {@code PROP_*}
     * констант — inventory generator в отдельном Spring test module (вне scope этого правила).
     */
    @ArchTest
    static final ArchRule no_reflection_bypass_of_narrowed_visibility =
            noClasses()
                    .that().resideInAPackage(PROPAGATION_PACKAGE)
                    .should().callMethodWhere(isReflectionBypass())
                    .because("Class.forName/getDeclaredField/setAccessible в propagation-пакете — обход "
                            + "сужённой visibility, а не легитимный consumer (G-PROP-3)");

    /**
     * G-PROP-4: implementation-decorators propagation package не public — единственные public
     * production types: {@code TraceControlPropagatorProvider} (SPI),
     * {@code BaggagePropagationCustomizer} и {@code PlatformPropagatorsDefaultsCustomizer}
     * (composition root вне package), {@code PlatformPropagationGate} (cross-package consumers)
     * и {@code PropagationDefaults} (inventory contract).
     */
    @ArchTest
    static final ArchRule decorators_are_not_public =
            classes()
                    .that().haveSimpleName("FilteringBaggagePropagator")
                    .or().haveSimpleName("SafeTextMapPropagator")
                    .or().haveSimpleName("FailClosedCorrelationBaggagePropagator")
                    .or().haveSimpleName("TraceControlPropagator")
                    .should().notBePublic()
                    .because("decorators — internal implementation propagation package, не public API (G-PROP-4)");

    /**
     * G-PROP-5: удалённые indirection-типы и прежние misleading names не должны появиться
     * повторно (защита от реинкарнации Detector/Builder и отката rename
     * {@code InboundTraceControlPropagator*} → {@code TraceControlPropagator*}).
     */
    @ArchTest
    static final ArchRule removed_indirection_types_do_not_exist =
            noClasses()
                    .should().haveSimpleName("BaggagePropagatorTypeDetector")
                    .orShould().haveSimpleName("InboundTraceControlPropagatorBuilder")
                    .orShould().haveSimpleName("InboundTraceControlPropagator")
                    .orShould().haveSimpleName("InboundTraceControlPropagatorProvider")
                    .because("Detector/Builder удалены в Slice 3; Inbound*-имена propagator/provider "
                            + "заменены на TraceControlPropagator* (двусторонний TextMapPropagator) — G-PROP-5");

    /**
     * G-PROP-8: прямые вызовы {@code PlatformPropagationGate.shared()}/{@code bindAcceptedPolicy(...)}/
     * {@code isEnabled()} разрешены только явно перечисленным consumers. Любой новый caller —
     * architecture violation, а не молчаливое расширение allowlist.
     */
    @ArchTest
    static final ArchRule only_named_consumers_call_propagation_gate =
            noClasses()
                    .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
                    .and().doNotHaveSimpleName("PlatformPropagationGate")
                    .and().doNotHaveSimpleName("BootstrapCoordinator")
                    .and().doNotHaveSimpleName("DiagnosticsControl")
                    .and().doNotHaveSimpleName("TraceControlPropagator")
                    .should().callMethodWhere(isPropagationGateAccessor())
                    .because("PlatformPropagationGate.shared()/bindAcceptedPolicy(...)/isEnabled() разрешены "
                            + "только BootstrapCoordinator (bind, activation transaction), "
                            + "DiagnosticsControl (read) и TraceControlPropagator (read isEnabled, same "
                            + "package, production) — G-PROP-8");

    private static DescribedPredicate<JavaMethodCall> isReflectionBypass() {
        return new DescribedPredicate<>("вызов Class.forName/getDeclaredField*/setAccessible") {
            @Override
            public boolean test(JavaMethodCall call) {
                String owner = call.getTargetOwner().getName();
                String name = call.getTarget().getName();
                return ("java.lang.Class".equals(owner)
                        && (name.equals("forName") || name.startsWith("getDeclaredField")))
                        || name.equals("setAccessible");
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> isPropagationGateAccessor() {
        return new DescribedPredicate<>("вызов PlatformPropagationGate.shared/bindAcceptedPolicy/isEnabled") {
            @Override
            public boolean test(JavaMethodCall call) {
                if (!GATE_FQN.equals(call.getTargetOwner().getName())) {
                    return false;
                }
                String name = call.getTarget().getName();
                return name.equals("shared") || name.equals("bindAcceptedPolicy") || name.equals("isEnabled");
            }
        };
    }

    /**
     * G-PROP-7: public поля {@link PropagationDefaults} — ровно три {@code PROP_*} (static final
     * {@code String}), ничего больше; default values и accessor methods не public. Baggage
     * {@code PROP_*} константы выведены отсюда в S2 (единственный владелец —
     * {@code BaggageExtensionConfig}); в этом классе остаются только header-имена.
     *
     * <p>Не выражено через {@code ArchCondition} на уровне полей (ArchUnit field-visibility API
     * не так лаконичен для точного count-инварианта), поэтому проверяется прямой reflection.
     */
    @Test
    @DisplayName("G-PROP-7: PropagationDefaults содержит ровно три public PROP_* и не более public-полей")
    void propagationDefaults_has_exactly_three_public_prop_fields() {
        List<Field> publicFields = List.of(PropagationDefaults.class.getFields());
        assertThat(publicFields)
                .as("единственные public поля PropagationDefaults — три header PROP_* константы")
                .hasSize(3)
                .allSatisfy(field -> {
                    assertThat(field.getName()).startsWith("PROP_");
                    assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
                    assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
                    assertThat(field.getType()).isEqualTo(String.class);
                });
    }

    /**
     * G-PROP-4 (продолжение): только {@code SafeTextMapPropagator} может содержать
     * {@code catch (Throwable ...)}/{@code catch (Exception ...)} в propagation package —
     * единственная failure-isolation boundary цепочки (§1.3 плана). Текстовая проверка source,
     * так как ArchUnit не разбирает тела catch-блоков на уровне bytecode-API.
     */
    @Test
    @DisplayName("G-PROP-4: только SafeTextMapPropagator ловит Throwable/Exception в propagation package")
    void only_safe_wrapper_catches_throwable_or_exception() throws IOException {
        Path propagationSrc = Path.of("src", "main", "java", "space", "br1440", "platform",
                "tracing", "otel", "javaagent", "propagation");
        assertThat(Files.isDirectory(propagationSrc)).as("propagation source dir существует").isTrue();

        Pattern catchThrowableOrException = Pattern.compile("catch\\s*\\(\\s*(Throwable|Exception)\\b");
        try (Stream<Path> files = Files.walk(propagationSrc)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                boolean isSafeWrapper = file.getFileName().toString().equals("SafeTextMapPropagator.java");
                String content = Files.readString(file);
                boolean catches = catchThrowableOrException.matcher(content).find();
                if (!isSafeWrapper) {
                    assertThat(catches)
                            .as("%s не должен ловить Throwable/Exception — единственная failure-isolation "
                                    + "boundary в цепочке это SafeTextMapPropagator (G-PROP-4)", file)
                            .isFalse();
                }
            }
        }
    }
}
