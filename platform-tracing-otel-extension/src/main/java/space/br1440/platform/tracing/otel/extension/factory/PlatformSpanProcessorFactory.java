package space.br1440.platform.tracing.otel.extension.factory;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.extension.configuration.ValidationMode;
import space.br1440.platform.tracing.otel.extension.configuration.ValidationModeResolver;
import space.br1440.platform.tracing.otel.extension.configuration.enums.ScrubbingMissingKeyPolicy;
import space.br1440.platform.tracing.otel.extension.configuration.enums.ScrubbingRulesValidationMode;
import space.br1440.platform.tracing.otel.extension.configuration.spi.JavaAgentExtensionPaths;
import space.br1440.platform.tracing.otel.extension.configuration.ScrubbingExtensionConfig;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.extension.processor.BaggageSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ClassificationSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.EnrichingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.MetricsSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSensitiveDataRules;
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

            if (extConfig.baggage().enabled()) {
                List<String> allowlist = extConfig.baggage().allowlistKeys();
                List<String> denyPatterns = extConfig.baggage().denyPatterns();
                if (!allowlist.isEmpty()) {
                    delegates.add(new BaggageSpanProcessor(new HashSet<>(allowlist), denyPatterns));
                }
            }

            if (extConfig.enriching().enabled()) {
                delegates.add(new EnrichingSpanProcessor(extConfig.enriching().remoteServicePriority()));
            }

            if (extConfig.scrubbing().enabled()) {
                List<SensitiveDataRule> rules = collectScrubbingRules(extConfig.scrubbing(), config);
                if (!rules.isEmpty()) {
                    String hmacKey = extConfig.scrubbing().hmacKey();
                    String missingKeyPolicy = extConfig.scrubbing().missingKeyPolicy();
                    boolean failFast = ScrubbingMissingKeyPolicy.FAIL_FAST.value()
                            .equalsIgnoreCase(missingKeyPolicy);

                    ScrubbingSpanProcessor scrubbing = new ScrubbingSpanProcessor(rules, hmacKey, failFast);
                    delegates.add(scrubbing);
                    jmxRegistrar.setScrubbing(scrubbing);
                }
            }

            if (extConfig.validation().enabled()) {
                // SP-02: resolve effective validation mode through the environment safety guard.
                ValidationModeResolver.Resolution resolution = ValidationModeResolver.resolve(
                        extConfig.validation().platformEnvironment(),
                        extConfig.validation().requestedValidationMode());
                ValidatingSpanProcessor validating = new ValidatingSpanProcessor(
                        resolution.effectiveMode() == ValidationMode.STRICT,
                        resolution.strictRuntimeAllowed());
                delegates.add(validating);
                jmxRegistrar.setValidating(validating);
            }

            if (extConfig.classification().enabled()) {
                delegates.add(new ClassificationSpanProcessor(
                        extConfig.classification().slowThreshold(),
                        extConfig.classification().normalThreshold()
                ));
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
                PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(delegates);
                tpBuilder.addSpanProcessor(composite);
                jmxRegistrar.setComposite(composite);
            }

            jmxRegistrar.tryRegisterMBeans();

            return tpBuilder;
        } catch (Throwable t) {
            log.error("[platform-tracing] registerSpanProcessors failed: {}", t.toString());
            throw t;
        }
    }

    private List<SensitiveDataRule> collectScrubbingRules(ScrubbingExtensionConfig scrubbing,
                                                          ConfigProperties config) {
        List<String> primaryNames = scrubbing.builtInRules();
        List<String> additionalNames = ScrubbingRulesLoader.load(scrubbing.rulesConfig(), ScrubbingRulesLoader.class.getClassLoader());

        Set<String> dedupNames = new LinkedHashSet<>(primaryNames);
        dedupNames.addAll(additionalNames);

        List<SensitiveDataRule> builtIn = new ArrayList<>();
        for (String name : dedupNames) {
            SensitiveDataRule rule = BuiltInSensitiveDataRules.resolve(name);
            if (rule != null) {
                builtIn.add(rule);
            } else {
                log.warn("[scrubbing] Unknown built-in sensitive data rule: '{}'", name);
            }
        }

        List<SensitiveDataRule> bundledSpi = new ArrayList<>();
        ServiceLoader.load(SensitiveDataRule.class, PlatformSpanProcessorFactory.class.getClassLoader())
                .forEach(bundledSpi::add);

        ExtensionRuleLoader.ValidationMode validationMode = resolveValidationMode(scrubbing);
        Set<String> otelExtensionPaths = collectOtelExtensionCanonicalPaths(config);

        ExtensionRuleLoader extensionRuleLoader = new ExtensionRuleLoader(validationMode, otelExtensionPaths);
        String extensionsProperty = scrubbing.rulesExtensions();
        List<SensitiveDataRule> custom = extensionRuleLoader.load(extensionsProperty);

        emitStartupDiagnostics(builtIn, bundledSpi, custom, extensionsProperty, extensionRuleLoader);
        return mergeRules(builtIn, bundledSpi, custom);
    }

    private static void emitStartupDiagnostics(List<SensitiveDataRule> builtInRules,
                                               List<SensitiveDataRule> bundledSpiRules,
                                               List<SensitiveDataRule> customRules,
                                               String rulesExtensionsProperty,
                                               ExtensionRuleLoader extensionRuleLoader) {
        long clamped = customRules.stream()
                .filter(r -> !r.critical() && r.priority() < PriorityHardening.CUSTOM_PRIORITY_FLOOR)
                .count();

        String loadingMode = Strings.isBlank(rulesExtensionsProperty) ? "NONE" : "PLATFORM_RULES_EXTENSIONS";

        StartupDiagnostics.emit(
                builtInRules.size(), bundledSpiRules.size(), customRules.size(),
                loadingMode, extensionRuleLoader.getFailedProviders(),
                (int) clamped, 0, extensionRuleLoader.getFailedEntries()
        );
    }

    private static List<SensitiveDataRule> mergeRules(List<SensitiveDataRule> builtIn,
                                                      List<SensitiveDataRule> bundledSpi,
                                                      List<SensitiveDataRule> custom) {
        Set<String> seenNames = new LinkedHashSet<>();
        List<SensitiveDataRule> rules = new ArrayList<>();
        for (SensitiveDataRule rule : builtIn) {
            addRuleIfNew(rules, seenNames, rule);
        }

        for (SensitiveDataRule rule : bundledSpi) {
            addRuleIfNew(rules, seenNames, rule);
        }

        for (SensitiveDataRule rule : custom) {
            addRuleIfNew(rules, seenNames, rule);
        }

        return rules;
    }

    private static void addRuleIfNew(List<SensitiveDataRule> rules, Set<String> seenNames, SensitiveDataRule rule) {
        if (seenNames.add(rule.name())) {
            rules.add(rule);
        }
    }

    private static ExtensionRuleLoader.ValidationMode resolveValidationMode(ScrubbingExtensionConfig scrubbing) {
        String raw = scrubbing.rulesValidationMode();
        if (ScrubbingRulesValidationMode.STRICT.value().equalsIgnoreCase(raw)) {
            return ExtensionRuleLoader.ValidationMode.STRICT;
        }

        return ExtensionRuleLoader.ValidationMode.LENIENT;
    }

    private static Set<String> collectOtelExtensionCanonicalPaths(ConfigProperties config) {
        String raw = JavaAgentExtensionPaths.resolveRawValue(config);
        if (Strings.isBlank(raw)) {
            return Set.of();
        }

        Set<String> canonicalPaths = new HashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            File path = new File(trimmed);
            if (path.isDirectory()) {
                File[] jars = path.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        canonicalPaths.add(canonicalize(jar));
                    }
                }
            } else {
                canonicalPaths.add(canonicalize(path));
            }
        }

        return canonicalPaths;
    }

    private static String canonicalize(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }
}
