package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Точный гейт публичной поверхности пакетов {@code ..scrubbing..} и {@code ..exporter..}
 * (ADR-scrubbing-hybrid-processor-export-boundary.md, «Границы установки»).
 *
 * <p>Опасность этих пакетов не в отдельном методе, а в тихом росте поверхности: каждый новый
 * публичный тип, конструктор или статическая фабрика — это потенциальный способ собрать
 * scrubbing-компонент мимо {@code PlatformScrubberFactory} и session-scoped installation barrier.
 * Диагностические instance-getter'ы такого риска не несут и намеренно не пиннятся, иначе гейт
 * превратится в шум на каждый новый счётчик.
 *
 * <p>Расширение списка допустимо, но только осознанно: добавление строки сюда — это заявление,
 * что новая точка входа не обходит barrier.
 */
class ScrubbingPublicSurfaceTest {

    private static final List<String> SCRUBBING_PACKAGES = List.of(
            "space.br1440.platform.tracing.otel.javaagent.scrubbing",
            "space.br1440.platform.tracing.otel.javaagent.exporter");

    /**
     * {@code ScrubbingRuntimeSupport} остаётся публичным осознанно: он нужен unit-тестам,
     * JMH-бенчмаркам и E2E из других модулей, а production-путь к нему закрыт правилом
     * {@code SafeBoundaryArchTest.production_code_builds_scrubbing_runtime_only_through_the_factory}.
     */
    private static final Set<String> ALLOWED_PUBLIC_TYPES = Set.of(
            "space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter",
            "space.br1440.platform.tracing.otel.javaagent.exporter.SpanExporterMetrics",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.MonotonicCounter",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingFailureLog",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingKeyNormalizer",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRulesLoader",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime$Installation",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeSupport",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingSnapshot",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.StableScrubbingRule",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker$State",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.diagnostics.FailedProviderReason",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.diagnostics.StartupDiagnostics",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.IpPrefixTruncator",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.PriorityHardening",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleExecutionOutcome",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOutcome",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader$FailedEntry",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader$ValidationMode",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.policy.ScrubbingRuleResolution",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.policy.ScrubbingRuleResolutionResult",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules");

    /**
     * Единственный публичный конструктор {@code SafeSpanExporter} — тот, что требует
     * {@code ScrubbingRuntime}: fixture-конструкторы с disabled runtime стали package-private
     * именно из-за возможности подтвердить installation barrier пустой обёрткой.
     */
    private static final Set<String> ALLOWED_PUBLIC_CONSTRUCTORS = Set.of(
            "space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter"
                    + ".<init>(io.opentelemetry.sdk.trace.export.SpanExporter,"
                    + " space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource,"
                    + " space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory.<init>()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory"
                    + ".<init>(java.util.function.Supplier)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException"
                    + ".<init>(java.lang.String, java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException"
                    + ".<init>(java.lang.String, java.lang.String, java.lang.Throwable)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingSnapshot"
                    + ".<init>(boolean, java.util.List, long, java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker"
                    + ".<init>(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker"
                    + ".<init>(java.lang.String, int, long, long)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule"
                    + ".<init>(space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule,"
                    + " space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome"
                    + ".<init>(space.br1440.platform.tracing.api.spi.ScrubbingDecision, long, long, long)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOutcome"
                    + ".<init>(space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleExecutionOutcome,"
                    + " space.br1440.platform.tracing.api.spi.ScrubbingDecision)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule"
                    + ".<init>(space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule,"
                    + " space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader"
                    + ".<init>(space.br1440.platform.tracing.otel.javaagent.scrubbing.loader"
                    + ".ExtensionRuleLoader$ValidationMode, java.util.Set)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader$FailedEntry"
                    + ".<init>(java.lang.String,"
                    + " space.br1440.platform.tracing.otel.javaagent.scrubbing.diagnostics.FailedProviderReason)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.policy.ScrubbingRuleResolutionResult"
                    + ".<init>(java.util.List, java.util.List)");

    /**
     * {@code ScrubbingRuntime.create} и {@code ScrubbingRuntime.disabled} строят runtime вне
     * сессии, поэтому остаются под отдельным ArchUnit-запретом на production-вызовы; здесь они
     * фиксируются как известная и осознанно оставленная поверхность.
     */
    private static final Set<String> ALLOWED_PUBLIC_STATIC_METHODS = Set.of(
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingFailureLog"
                    + ".error(space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger,"
                    + " java.lang.String, java.lang.Throwable)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingFailureLog"
                    + ".warn(space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger,"
                    + " java.lang.String, java.lang.Throwable)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingKeyNormalizer"
                    + ".normalize(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRulesLoader"
                    + ".load(java.lang.String, java.lang.ClassLoader)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime"
                    + ".create(java.util.List, long, java.lang.String,"
                    + " space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret, boolean)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime.disabled()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeSupport"
                    + ".fromBuiltInRules(java.util.List,"
                    + " space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret, boolean)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingSnapshot"
                    + ".fromRules(boolean, java.util.List, long, java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingSnapshot"
                    + ".toExecutableRules(java.util.List)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.StableScrubbingRule"
                    + ".captureExternal(space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker$State"
                    + ".valueOf(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker$State"
                    + ".values()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.diagnostics.FailedProviderReason"
                    + ".valueOf(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.diagnostics.FailedProviderReason.values()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.diagnostics.StartupDiagnostics"
                    + ".emit(int, int, int, java.lang.String, int, int, int, java.util.List)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.IpPrefixTruncator"
                    + ".truncate(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine"
                    + ".evaluate(java.util.List, java.lang.String, java.lang.Object)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine"
                    + ".evaluateDetailed(java.util.List, java.lang.String, java.lang.Object)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.PriorityHardening"
                    + ".sortAndClamp(java.util.List)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.PriorityHardening"
                    + ".sortAndClampRuntimeRules(java.util.List)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleExecutionOutcome"
                    + ".valueOf(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleExecutionOutcome.values()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin.valueOf(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin.values()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOutcome"
                    + ".criticalFailure(space.br1440.platform.tracing.api.spi.ScrubbingDecision)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOutcome"
                    + ".decision(space.br1440.platform.tracing.api.spi.ScrubbingDecision)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOutcome.keep()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOutcome.nonCriticalFailure()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOutcome.skippedCircuitOpen()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader$ValidationMode"
                    + ".valueOf(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader$ValidationMode"
                    + ".values()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.policy.ScrubbingRuleResolution"
                    + ".resolveRuleNames([Ljava.lang.String;)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.policy.ScrubbingRuleResolution"
                    + ".validateRuleNames([Ljava.lang.String;)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules"
                    + ".defaultConfigNames()",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules"
                    + ".lookup(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules"
                    + ".resolve(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules"
                    + ".valueOf(java.lang.String)",
            "space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules"
                    + ".values()");

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(SCRUBBING_PACKAGES);

    @Test
    @DisplayName("публичные типы scrubbing и exporter совпадают с зафиксированным списком")
    void public_types_match_the_pinned_surface() {
        Set<String> actual = new TreeSet<>();
        for (JavaClass type : PRODUCTION_CLASSES) {
            if (type.getModifiers().contains(JavaModifier.PUBLIC)) {
                actual.add(type.getName());
            }
        }

        assertThat(actual)
                .as("новый публичный тип в ..scrubbing../..exporter.. — это новая точка входа мимо "
                        + "PlatformScrubberFactory; добавьте его в ALLOWED_PUBLIC_TYPES осознанно")
                .containsExactlyInAnyOrderElementsOf(ALLOWED_PUBLIC_TYPES);
    }

    @Test
    @DisplayName("публичные конструкторы scrubbing и exporter совпадают с зафиксированным списком")
    void public_constructors_match_the_pinned_surface() {
        Set<String> actual = new TreeSet<>();
        for (JavaClass type : PRODUCTION_CLASSES) {
            if (!type.getModifiers().contains(JavaModifier.PUBLIC)) {
                continue;
            }
            type.getConstructors().stream()
                    .filter(constructor -> constructor.getModifiers().contains(JavaModifier.PUBLIC))
                    .forEach(constructor -> actual.add(constructor.getFullName()));
        }

        assertThat(actual)
                .as("публичный конструктор в этих пакетах позволяет собрать scrubbing-компонент "
                        + "вне сессии и подтвердить installation barrier")
                .containsExactlyInAnyOrderElementsOf(ALLOWED_PUBLIC_CONSTRUCTORS);
    }

    @Test
    @DisplayName("публичные статические фабрики scrubbing и exporter совпадают с зафиксированным списком")
    void public_static_methods_match_the_pinned_surface() {
        Set<String> actual = new TreeSet<>();
        for (JavaClass type : PRODUCTION_CLASSES) {
            if (!type.getModifiers().contains(JavaModifier.PUBLIC)) {
                continue;
            }
            type.getMethods().stream()
                    .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                    .filter(method -> method.getModifiers().contains(JavaModifier.STATIC))
                    .forEach(method -> actual.add(method.getFullName()));
        }

        assertThat(actual)
                .as("статическая фабрика — самый незаметный способ построить runtime мимо "
                        + "PlatformScrubberFactory")
                .containsExactlyInAnyOrderElementsOf(ALLOWED_PUBLIC_STATIC_METHODS);
    }
}
