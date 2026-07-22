package space.br1440.platform.tracing.otel.extension.factory;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.core.control.protocol.RuntimePolicyControlHandler;
import space.br1440.platform.tracing.core.control.protocol.RuntimeControlMutationPolicy;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.extension.configuration.ValidationMode;
import space.br1440.platform.tracing.otel.extension.configuration.ValidationModeResolver;
import space.br1440.platform.tracing.otel.extension.configuration.enums.ScrubbingMissingKeyPolicy;
import space.br1440.platform.tracing.otel.extension.configuration.enums.ScrubbingRulesValidationMode;
import space.br1440.platform.tracing.otel.extension.configuration.spi.JavaAgentExtensionPaths;
import space.br1440.platform.tracing.otel.extension.configuration.ScrubbingExtensionConfig;
import space.br1440.platform.tracing.otel.extension.control.JmxRuntimePolicyApplier;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.extension.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.extension.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.extension.processor.BaggageSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ClassificationSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.EnrichingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.MetricsSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.extension.scrubbing.ScrubbingRulesLoader;
import space.br1440.platform.tracing.otel.extension.scrubbing.diagnostics.StartupDiagnostics;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.PriorityHardening;
import space.br1440.platform.tracing.otel.extension.scrubbing.loader.ExtensionRuleLoader;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public final class PlatformSpanProcessorFactory {

    private final PlatformTracingJmxRegistrar jmxRegistrar;

    public PlatformSpanProcessorFactory(PlatformTracingJmxRegistrar jmxRegistrar) {
        this.jmxRegistrar = jmxRegistrar;
    }

    public SdkTracerProviderBuilder registerSpanProcessors(SdkTracerProviderBuilder tpBuilder,
                                                           ExtensionConfig extConfig,
                                                           ConfigProperties config) {
        try {
            List<SpanProcessor> delegates = new ArrayList<>();

            // --- Optional processors ---

            if (extConfig.baggage().enabled()) {
                List<String> allowlist    = extConfig.baggage().allowlistKeys();
                List<String> denyPatterns = extConfig.baggage().denyPatterns();
                if (!allowlist.isEmpty()) {
                    delegates.add(new BaggageSpanProcessor(new HashSet<>(allowlist), denyPatterns));
                }
            }

            if (extConfig.enriching().enabled()) {
                delegates.add(new EnrichingSpanProcessor(
                        extConfig.enriching().remoteServicePriority()));
            }

            if (extConfig.scrubbing().enabled()) {
                List<SpanAttributeScrubbingRule> rules =
                        collectScrubbingRules(extConfig.scrubbing(), config);
                if (!rules.isEmpty()) {
                    String  hmacKey          = extConfig.scrubbing().hmacKey();
                    String  missingKeyPolicy = extConfig.scrubbing().missingKeyPolicy();
                    boolean failFast         = ScrubbingMissingKeyPolicy.FAIL_FAST.value()
                            .equalsIgnoreCase(missingKeyPolicy);

                    ScrubbingSpanProcessor scrubbing =
                            new ScrubbingSpanProcessor(rules, hmacKey, failFast);
                    delegates.add(scrubbing);
                    jmxRegistrar.setScrubbing(scrubbing);
                    jmxRegistrar.extensionReadiness().markInstalled(
                            PlatformExtensionCapability.SANITIZER_INSTALLED);
                }
            }

            // --- Validation + control-protocol handler wiring ---
            //
            // The control-protocol handler is always wired here so that
            // PlatformControlProtocolMBean is available regardless of
            // whether validation is enabled in config.  When validation
            // is disabled the ValidatingSpanProcessor used inside the
            // applier is a no-op stub (enabled=false, strict=false).

            ValidatingSpanProcessor validatingForHandler;

            if (extConfig.validation().enabled()) {
                ValidationModeResolver.Resolution resolution = ValidationModeResolver.resolve(
                        extConfig.validation().platformEnvironment(),
                        extConfig.validation().requestedValidationMode());
                ValidatingSpanProcessor validating = new ValidatingSpanProcessor(
                        resolution.effectiveMode() == ValidationMode.STRICT,
                        resolution.strictRuntimeAllowed());
                delegates.add(validating);
                jmxRegistrar.setValidating(validating);
                validatingForHandler = validating;
            } else {
                // Stub: validation is off at startup but operator may enable
                // it later via JMX control protocol.
                ValidatingSpanProcessor stub = new ValidatingSpanProcessor(false, false);
                stub.tryApplyPolicyUpdate(false, false, "startup");
                jmxRegistrar.setValidating(stub);
                validatingForHandler = stub;
            }

            wireControlProtocolHandler(validatingForHandler,
                    extConfig.control().runtimeMutationEnabled());

            // --- Remaining optional processors ---

            if (extConfig.classification().enabled()) {
                delegates.add(new ClassificationSpanProcessor(
                        extConfig.classification().slowThreshold(),
                        extConfig.classification().normalThreshold()));
            }

            if (extConfig.watchdog().enabled()) {
                SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                        extConfig.watchdog().spanTimeout(),
                        extConfig.watchdog().traceTimeout(),
                        extConfig.watchdog().scanInterval());
                delegates.add(watchdog);
                jmxRegistrar.setWatchdog(watchdog);
            }

            if (extConfig.metrics().enabled()) {
                MetricsSpanProcessor metricsProcessor = new MetricsSpanProcessor();
                delegates.add(metricsProcessor);
                jmxRegistrar.setMetrics(metricsProcessor);
            }

            if (!delegates.isEmpty()) {
                PlatformCompositeSpanProcessor composite =
                        new PlatformCompositeSpanProcessor(delegates);
                tpBuilder.addSpanProcessor(composite);
                jmxRegistrar.setComposite(composite);
                jmxRegistrar.extensionReadiness().markInstalled(
                        PlatformExtensionCapability.REQUIRED_SPAN_PROCESSORS_INSTALLED);
            }

            jmxRegistrar.tryRegisterMBeans();

            return tpBuilder;
        } catch (Throwable t) {
            log.error("[platform-tracing] registerSpanProcessors failed: {}", t.toString());
            throw t;
        }
    }

    // =========================================================================
    // Control-protocol handler wiring
    // =========================================================================

    /**
     * Builds and wires the {@link RuntimePolicyControlHandler} into the
     * registrar.
     *
     * <p>This method relies on the registrar having already received the
     * {@link space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder}
     * via {@link PlatformTracingJmxRegistrar#setConfigHolder} (done by
     * {@link PlatformSamplerFactory}).
     *
     * <p>A shared {@link LongAdder} counter from the registrar is used so
     * that failed control-protocol invocations are reflected in the
     * diagnostics MBean together with other invalid-config events.
     */
    private void wireControlProtocolHandler(ValidatingSpanProcessor validating,
                                            boolean runtimeMutationEnabled) {
        // samplingControl reuses the already-registered SamplerStateHolder
        // inside the registrar — no new state is created.
        PlatformSamplingControl samplingControl = jmxRegistrar.buildSamplingControl();
        LongAdder counter = jmxRegistrar.sharedInvalidConfigCounter();

        PlatformValidationControl validationControl =
                new PlatformValidationControl(validating, counter);

        JmxRuntimePolicyApplier applier =
                new JmxRuntimePolicyApplier(samplingControl, validationControl);

        RuntimePolicyControlHandler handler =
                new RuntimePolicyControlHandler(
                        applier,
                        RuntimeControlMutationPolicy.startupConfigured(runtimeMutationEnabled));

        jmxRegistrar.setControlHandler(handler);
    }

    // =========================================================================
    // Scrubbing helpers (unchanged)
    // =========================================================================

    private List<SpanAttributeScrubbingRule> collectScrubbingRules(
            ScrubbingExtensionConfig scrubbing,
            ConfigProperties config) {
        List<String> primaryNames    = scrubbing.builtInRules();
        List<String> additionalNames = ScrubbingRulesLoader.load(
                scrubbing.rulesConfig(),
                ScrubbingRulesLoader.class.getClassLoader());

        Set<String> dedupNames = new LinkedHashSet<>(primaryNames);
        dedupNames.addAll(additionalNames);

        List<SpanAttributeScrubbingRule> builtIn = new ArrayList<>();
        for (String name : dedupNames) {
            SpanAttributeScrubbingRule rule = BuiltInSpanAttributeScrubbingRules.resolve(name);
            if (rule != null) {
                builtIn.add(rule);
            } else {
                log.warn("[scrubbing] Unknown built-in sensitive data rule: '{}'", name);
            }
        }

        List<SpanAttributeScrubbingRule> bundledSpi = new ArrayList<>();
        ServiceLoader.load(SpanAttributeScrubbingRule.class,
                        PlatformSpanProcessorFactory.class.getClassLoader())
                .forEach(bundledSpi::add);

        ExtensionRuleLoader.ValidationMode validationMode =
                resolveValidationMode(scrubbing);
        Set<String> otelExtensionPaths =
                collectOtelExtensionCanonicalPaths(config);

        ExtensionRuleLoader extensionRuleLoader =
                new ExtensionRuleLoader(validationMode, otelExtensionPaths);
        String extensionsProperty = scrubbing.rulesExtensions();
        List<SpanAttributeScrubbingRule> custom =
                extensionRuleLoader.load(extensionsProperty);

        emitStartupDiagnostics(builtIn, bundledSpi, custom,
                extensionsProperty, extensionRuleLoader);
        return mergeRules(builtIn, bundledSpi, custom);
    }

    private static void emitStartupDiagnostics(
            List<SpanAttributeScrubbingRule> builtInRules,
            List<SpanAttributeScrubbingRule> bundledSpiRules,
            List<SpanAttributeScrubbingRule> customRules,
            String rulesExtensionsProperty,
            ExtensionRuleLoader extensionRuleLoader) {
        long clamped = customRules.stream()
                .filter(r -> !r.critical()
                        && r.priority() < PriorityHardening.CUSTOM_PRIORITY_FLOOR)
                .count();
        String loadingMode = Strings.isBlank(rulesExtensionsProperty)
                ? "NONE" : "PLATFORM_RULES_EXTENSIONS";
        StartupDiagnostics.emit(
                builtInRules.size(), bundledSpiRules.size(), customRules.size(),
                loadingMode, extensionRuleLoader.getFailedProviders(),
                (int) clamped, 0, extensionRuleLoader.getFailedEntries());
    }

    private static List<SpanAttributeScrubbingRule> mergeRules(
            List<SpanAttributeScrubbingRule> builtIn,
            List<SpanAttributeScrubbingRule> bundledSpi,
            List<SpanAttributeScrubbingRule> custom) {
        Set<String> seenNames = new LinkedHashSet<>();
        List<SpanAttributeScrubbingRule> rules = new ArrayList<>();
        for (SpanAttributeScrubbingRule rule : builtIn)    { addIfNew(rules, seenNames, rule); }
        for (SpanAttributeScrubbingRule rule : bundledSpi) { addIfNew(rules, seenNames, rule); }
        for (SpanAttributeScrubbingRule rule : custom)     { addIfNew(rules, seenNames, rule); }
        return rules;
    }

    private static void addIfNew(List<SpanAttributeScrubbingRule> rules,
                                  Set<String> seenNames,
                                  SpanAttributeScrubbingRule rule) {
        if (seenNames.add(rule.name())) {
            rules.add(rule);
        }
    }

    private static ExtensionRuleLoader.ValidationMode resolveValidationMode(
            ScrubbingExtensionConfig scrubbing) {
        String raw = scrubbing.rulesValidationMode();
        return ScrubbingRulesValidationMode.STRICT.value().equalsIgnoreCase(raw)
                ? ExtensionRuleLoader.ValidationMode.STRICT
                : ExtensionRuleLoader.ValidationMode.LENIENT;
    }

    private static Set<String> collectOtelExtensionCanonicalPaths(
            ConfigProperties config) {
        String raw = JavaAgentExtensionPaths.resolveRawValue(config);
        if (Strings.isBlank(raw)) {
            return Set.of();
        }
        Set<String> canonicalPaths = new HashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            File path = new File(trimmed);
            if (path.isDirectory()) {
                File[] jars = path.listFiles(
                        f -> f.isFile() && f.getName().endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) canonicalPaths.add(canonicalize(jar));
                }
            } else {
                canonicalPaths.add(canonicalize(path));
            }
        }
        return canonicalPaths;
    }

    private static String canonicalize(File f) {
        try   { return f.getCanonicalPath(); }
        catch (IOException e) { return f.getAbsolutePath(); }
    }
}
