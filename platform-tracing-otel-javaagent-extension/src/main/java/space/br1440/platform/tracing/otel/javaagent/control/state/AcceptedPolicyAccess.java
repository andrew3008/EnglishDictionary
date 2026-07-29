package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ValidationMode;
import space.br1440.platform.tracing.otel.javaagent.configuration.ValidationModeResolver;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicySnapshotFactory;

/**
 * Внутренний composition adapter к единственному accepted-state owner Agent.
 *
 * <p>Каждый метод изменения выполняет ровно одну expected-base CAS-попытку. Retry и rebase
 * отсутствуют. Отдельный carrier хранит только временно поддерживаемые Category B sampling rules;
 * он подлежит удалению вместе с legacy setters в P6.
 */
public final class AcceptedPolicyAccess {

    private final AcceptedPolicyStateStore store;
    private final boolean strictRuntimeAllowed;
    private final AtomicReference<SamplingPolicySnapshot> legacySamplingRules;

    private AcceptedPolicyAccess(
            AcceptedPolicyStateStore store,
            boolean strictRuntimeAllowed,
            SamplingPolicySnapshot legacySamplingRules) {
        this.store = Objects.requireNonNull(store, "store");
        this.strictRuntimeAllowed = strictRuntimeAllowed;
        this.legacySamplingRules = new AtomicReference<>(
                Objects.requireNonNull(legacySamplingRules, "legacySamplingRules"));
    }

    /**
     * Строит один accepted-state owner из окончательной Agent startup-конфигурации.
     */
    public static AcceptedPolicyAccess fromStartup(ExtensionConfig config) {
        Objects.requireNonNull(config, "config");
        ValidationModeResolver.Resolution validation = ValidationModeResolver.resolve(
                config.validation().platformEnvironment(),
                config.validation().requestedValidationMode());
        return create(
                config.sampling(),
                config.validation().enabled(),
                validation.effectiveMode() == ValidationMode.STRICT,
                validation.strictRuntimeAllowed());
    }

    /**
     * Изолированный owner для named sampler SPI до его привязки composition root.
     */
    public static AcceptedPolicyAccess fromSampling(SamplingExtensionConfig sampling) {
        return create(sampling, true, false, false);
    }

    /**
     * Изолированный owner для unit-тестов компонентов.
     */
    public static AcceptedPolicyAccess isolatedDefaults() {
        return isolatedValidation(true, false, true);
    }

    public static AcceptedPolicyAccess isolatedValidation(
            boolean enabled,
            boolean strict,
            boolean strictRuntimeAllowed) {
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                true,
                1.0d,
                Map.of(),
                enabled,
                strict,
                true,
                true,
                PolicyLogLevel.INFO);
        SamplingPolicySnapshot rules = samplingRules(List.of(), Set.of());
        return new AcceptedPolicyAccess(
                new AcceptedPolicyStateStore(UUID.randomUUID().toString(), policy),
                strictRuntimeAllowed,
                rules);
    }

    public static AcceptedPolicyAccess isolatedSampling(
            boolean enabled,
            List<String> droppedRoutes,
            List<String> forceRecordValues,
            Map<String, Double> routeRatios,
            double defaultRatio) {
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                enabled,
                defaultRatio,
                routeRatios,
                false,
                false,
                true,
                true,
                PolicyLogLevel.INFO);
        SamplingPolicySnapshot rules =
                samplingRules(droppedRoutes, new HashSet<>(forceRecordValues));
        return new AcceptedPolicyAccess(
                new AcceptedPolicyStateStore(UUID.randomUUID().toString(), policy),
                true,
                rules);
    }

    public AcceptedPolicyView current() {
        return store.current();
    }

    public SamplingPolicySnapshot currentLegacySamplingRules() {
        return legacySamplingRules.get();
    }

    public boolean isStrictRuntimeAllowed() {
        return strictRuntimeAllowed;
    }

    /**
     * Activates the process-local private complete-snapshot control plane for this
     * accepted-state owner. Exactly one private writer shares {@link #store}.
     *
     * <p>Called once from Agent bootstrap ({@code PlatformAutoConfigurationCustomizer}).
     * Isolated unit fixtures must not call this method.
     *
     * @return closeable that unregisters marker/writer and releases the private MBeanServer
     */
    public AutoCloseable activatePrivateControlPlane() {
        PrivateControlMBeanServerOwner owner = new PrivateControlMBeanServerOwner(
                store.current().runtimeInstanceId(),
                store,
                new PolicyValidationConstraints(strictRuntimeAllowed));
        owner.initialize();
        return owner;
    }

    public AcceptedPolicyUpdateOutcome updateSampling(
            boolean enabled,
            double ratio,
            Map<String, Double> routeRatios) {
        AcceptedPolicyEnvelope witnessed = store.current();
        EffectiveTracingPolicy current = witnessed.policy();
        if (enabled == current.samplingEnabled()
                && Double.compare(ratio, current.samplingRatio()) == 0
                && Objects.equals(routeRatios, current.samplingRouteRatios())) {
            return AcceptedPolicyUpdateOutcome.UNCHANGED;
        }

        EffectiveTracingPolicy candidate;
        try {
            candidate = EffectiveTracingPolicy.of(
                    enabled,
                    ratio,
                    routeRatios,
                    current.validationEnabled(),
                    current.validationStrict(),
                    current.exporterEnabled(),
                    current.traceControlHeadersEnabled(),
                    current.diagnosticsLogLevel());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return AcceptedPolicyUpdateOutcome.REJECTED;
        }
        return commit(witnessed, candidate);
    }

    public AcceptedPolicyUpdateOutcome updateValidation(boolean enabled, boolean strict) {
        if (strict && !strictRuntimeAllowed) {
            return AcceptedPolicyUpdateOutcome.REJECTED;
        }
        return commit(current -> EffectiveTracingPolicy.of(
                current.samplingEnabled(),
                current.samplingRatio(),
                current.samplingRouteRatios(),
                enabled,
                strict,
                current.exporterEnabled(),
                current.traceControlHeadersEnabled(),
                current.diagnosticsLogLevel()));
    }

    public AcceptedPolicyUpdateOutcome updateExporter(boolean enabled) {
        return commit(current -> EffectiveTracingPolicy.of(
                current.samplingEnabled(),
                current.samplingRatio(),
                current.samplingRouteRatios(),
                current.validationEnabled(),
                current.validationStrict(),
                enabled,
                current.traceControlHeadersEnabled(),
                current.diagnosticsLogLevel()));
    }

    public AcceptedPolicyUpdateOutcome updateTraceControlHeaders(boolean enabled) {
        return commit(current -> EffectiveTracingPolicy.of(
                current.samplingEnabled(),
                current.samplingRatio(),
                current.samplingRouteRatios(),
                current.validationEnabled(),
                current.validationStrict(),
                current.exporterEnabled(),
                enabled,
                current.diagnosticsLogLevel()));
    }

    public AcceptedPolicyUpdateOutcome updateDiagnosticsLogLevel(String value) {
        PolicyLogLevel level;
        try {
            level = PolicyLogLevel.valueOf(
                    Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return AcceptedPolicyUpdateOutcome.REJECTED;
        }
        PolicyLogLevel acceptedLevel = level;
        return commit(current -> EffectiveTracingPolicy.of(
                current.samplingEnabled(),
                current.samplingRatio(),
                current.samplingRouteRatios(),
                current.validationEnabled(),
                current.validationStrict(),
                current.exporterEnabled(),
                current.traceControlHeadersEnabled(),
                acceptedLevel));
    }

    public AcceptedPolicyUpdateOutcome updateDropPaths(List<String> dropPaths) {
        try {
            SamplingPolicySnapshot previous = legacySamplingRules.get();
            SamplingPolicySnapshot next = samplingRules(
                    dropPaths,
                    previous.getForceRecordValues());
            return legacySamplingRules.compareAndSet(previous, next)
                    ? AcceptedPolicyUpdateOutcome.APPLIED
                    : AcceptedPolicyUpdateOutcome.STALE;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return AcceptedPolicyUpdateOutcome.REJECTED;
        }
    }

    public AcceptedPolicyUpdateOutcome updateForceRecordValues(Set<String> values) {
        try {
            SamplingPolicySnapshot previous = legacySamplingRules.get();
            SamplingPolicySnapshot next = samplingRules(previous.getDroppedRoutes(), values);
            return legacySamplingRules.compareAndSet(previous, next)
                    ? AcceptedPolicyUpdateOutcome.APPLIED
                    : AcceptedPolicyUpdateOutcome.STALE;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return AcceptedPolicyUpdateOutcome.REJECTED;
        }
    }

    private AcceptedPolicyUpdateOutcome commit(PolicyCandidateFactory candidateFactory) {
        AcceptedPolicyEnvelope witnessed = store.current();
        EffectiveTracingPolicy candidate;
        try {
            candidate = candidateFactory.create(witnessed.policy());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return AcceptedPolicyUpdateOutcome.REJECTED;
        }
        if (candidate.equals(witnessed.policy())) {
            return AcceptedPolicyUpdateOutcome.UNCHANGED;
        }
        return commit(witnessed, candidate);
    }

    private AcceptedPolicyUpdateOutcome commit(
            AcceptedPolicyEnvelope witnessed,
            EffectiveTracingPolicy candidate) {
        PolicyCommitResult result = store.commit(witnessed.base(), candidate);
        if (result.outcome() == PolicyCommitOutcome.APPLIED) {
            return AcceptedPolicyUpdateOutcome.APPLIED;
        }
        return AcceptedPolicyUpdateOutcome.STALE;
    }

    private static AcceptedPolicyAccess create(
            SamplingExtensionConfig sampling,
            boolean validationEnabled,
            boolean validationStrict,
            boolean strictRuntimeAllowed) {
        Map<String, Double> routeRatios = parseRouteRatios(sampling.routeRatios());
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                sampling.enabled(),
                sampling.ratio(),
                routeRatios,
                validationEnabled,
                validationStrict,
                true,
                true,
                PolicyLogLevel.INFO);
        SamplingPolicySnapshot rules = samplingRules(
                sampling.dropPaths(),
                new HashSet<>(sampling.forceRecordValues()));
        return new AcceptedPolicyAccess(
                new AcceptedPolicyStateStore(UUID.randomUUID().toString(), policy),
                strictRuntimeAllowed,
                rules);
    }

    private static Map<String, Double> parseRouteRatios(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> parsed = new HashMap<>(source.size());
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            try {
                parsed.put(entry.getKey(), Double.parseDouble(entry.getValue().trim()));
            } catch (NumberFormatException ignored) {
                // Невалидная startup route-ratio запись не становится частью accepted policy.
            }
        }
        return parsed;
    }

    private static SamplingPolicySnapshot samplingRules(
            List<String> dropPaths,
            Set<String> forceRecordValues) {
        return SamplingPolicySnapshotFactory.create(new SamplingPolicyProperties(
                true,
                1.0d,
                dropPaths,
                forceRecordValues,
                Map.of()));
    }

    @FunctionalInterface
    private interface PolicyCandidateFactory {
        EffectiveTracingPolicy create(EffectiveTracingPolicy current);
    }
}
