package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Supplier;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ScrubbingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.factory.ScrubbingMissingKeyPolicy;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.PriorityHardening;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.StableScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

public final class PlatformScrubberFactory {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(PlatformScrubberFactory.class);

    private final Supplier<List<SpanAttributeScrubbingRule>> spiScrubbingRuleSource;

    public PlatformScrubberFactory() {
        this(PlatformScrubberFactory::loadSpiScrubbingRules);
    }

    PlatformScrubberFactory(Supplier<List<SpanAttributeScrubbingRule>> spiScrubbingRuleSource) {
        this.spiScrubbingRuleSource = Objects.requireNonNull(spiScrubbingRuleSource, "spiScrubbingRuleSource must not be null");
    }

    public ScrubbingRuntime buildRuntime(ExtensionConfig extConfig) {
        Objects.requireNonNull(extConfig, "extConfig must not be null");

        if (!extConfig.scrubbing().enabled()) {
            return ScrubbingRuntime.disabled();
        }

        List<RuntimeRule> rules = collectClassified(extConfig.scrubbing());
        if (rules.isEmpty()) {
            throw new SanitizerBootstrapException(
                    SanitizerBootstrapException.RULE_SET_EMPTY,
                    "Scrubbing is enabled, but no effective sanitizing rules were loaded"
            );
        }

        boolean failFast = ScrubbingMissingKeyPolicy.FAIL_FAST.value()
                .equalsIgnoreCase(extConfig.scrubbing().missingKeyPolicy());

        return ScrubbingRuntime.create(rules, 1L, extConfig.scrubbing().hmacKey(), failFast);
    }

    private List<RuntimeRule> collectClassified(ScrubbingExtensionConfig scrubbing) {
        try {
            return collectScrubbingRules(scrubbing);
        } catch (SanitizerBootstrapException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classified(
                    SanitizerBootstrapException.RULE_LOADING_FAILED,
                    "Scrubbing rule collection failed",
                    ScrubbingLogEvent.SCRUBBING_RULE_COLLECTION_FAILED,
                    e
            );
        }
    }

    private static SanitizerBootstrapException classified(String code,
                                                          String message,
                                                          AgentLogEventType eventType,
                                                          Throwable failure) {
        PlatformThrowables.propagateIfFatal(failure);
        log.log(eventType, fields -> fields.cause(failure));
        return new SanitizerBootstrapException(
                code,
                message + " (" + failure.getClass().getName() + ")",
                failure
        );
    }

    private List<RuntimeRule> collectScrubbingRules(ScrubbingExtensionConfig scrubbing) {
        List<String> primaryNames = scrubbing.builtInRules();
        List<String> additionalNames = ScrubbingRulesLoader.load(
                scrubbing.rulesConfig(),
                ScrubbingRulesLoader.class.getClassLoader()
        );

        Set<String> dedupNames = new LinkedHashSet<>(primaryNames);
        dedupNames.addAll(additionalNames);

        List<SpanAttributeScrubbingRule> builtIn = new ArrayList<>();
        for (String name : dedupNames) {
            SpanAttributeScrubbingRule rule = BuiltInSpanAttributeScrubbingRules.resolve(name);
            if (rule != null) {
                builtIn.add(rule);
            } else {
                log.log(ScrubbingLogEvent.UNKNOWN_BUILT_IN_SCRUBBING_RULE,
                        fields -> fields.putString(AgentLogField.NAME, name));
            }
        }

        List<SpanAttributeScrubbingRule> bundledSpi = stabilizeBundledRules(spiScrubbingRuleSource.get());

        ExtensionRuleLoader.ValidationMode validationMode = resolveValidationMode(scrubbing);
        String extensionsProperty = scrubbing.rulesExtensions();
        try (ExtensionRuleLoader extensionRuleLoader = new ExtensionRuleLoader(validationMode)) {
            List<SpanAttributeScrubbingRule> custom = extensionRuleLoader.load(extensionsProperty);
            emitStartupDiagnostics(builtIn, bundledSpi, custom, extensionsProperty, extensionRuleLoader);
            return mergeRules(builtIn, bundledSpi, custom);
        }
    }

    private static List<SpanAttributeScrubbingRule> loadSpiScrubbingRules() {
        List<SpanAttributeScrubbingRule> bundledSpi = new ArrayList<>();
        try {
            ServiceLoader.load(
                            SpanAttributeScrubbingRule.class,
                            PlatformScrubberFactory.class.getClassLoader()
                    )
                    .forEach(bundledSpi::add);
        } catch (ServiceConfigurationError | RuntimeException failure) {
            throw classified(
                    SanitizerBootstrapException.RULE_LOADING_FAILED,
                    "Bundled scrubbing rule loading failed",
                    ScrubbingLogEvent.BUNDLED_SCRUBBING_RULE_LOADING_FAILED,
                    failure
            );
        }

        return bundledSpi;
    }

    private static List<SpanAttributeScrubbingRule> stabilizeBundledRules(List<SpanAttributeScrubbingRule> rules) {
        Objects.requireNonNull(rules, "spiScrubbingRuleSource result must not be null");

        List<SpanAttributeScrubbingRule> stableRules = new ArrayList<>(rules.size());
        for (SpanAttributeScrubbingRule rule : rules) {
            try {
                stableRules.add(StableScrubbingRule.captureExternal(rule));
            } catch (RuntimeException failure) {
                throw classified(
                        SanitizerBootstrapException.RULE_METADATA_INVALID,
                        "Bundled scrubbing rule metadata validation failed",
                        ScrubbingLogEvent.BUNDLED_SCRUBBING_RULE_METADATA_VALIDATION_FAILED,
                        failure
                );
            }
        }

        return List.copyOf(stableRules);
    }

    private static void emitStartupDiagnostics(List<SpanAttributeScrubbingRule> builtInRules,
                                               List<SpanAttributeScrubbingRule> bundledSpiRules,
                                               List<SpanAttributeScrubbingRule> customRules,
                                               String rulesExtensionsProperty,
                                               ExtensionRuleLoader extensionRuleLoader) {
        int clamped = Math.toIntExact(customRules.stream()
                .filter(r -> !r.critical() && r.priority() < PriorityHardening.CUSTOM_PRIORITY_FLOOR)
                .count());

        String loadingMode = StringUtils.hasText(rulesExtensionsProperty) ? "PLATFORM_RULES_EXTENSIONS" : "NONE";
        StartupDiagnostics.emit(
                builtInRules.size(), bundledSpiRules.size(), customRules.size(),
                loadingMode, extensionRuleLoader.getFailedProviders(),
                clamped, 0, extensionRuleLoader.getFailedEntries()
        );
    }

    private static List<RuntimeRule> mergeRules(List<SpanAttributeScrubbingRule> builtIn,
                                                List<SpanAttributeScrubbingRule> bundledSpi,
                                                List<SpanAttributeScrubbingRule> custom) {
        Set<String> seenNames = new LinkedHashSet<>();
        List<RuntimeRule> rules = new ArrayList<>();
        for (SpanAttributeScrubbingRule rule : builtIn) {
            addIfNew(rules, seenNames, rule, RuleOrigin.BUILT_IN);
        }

        for (SpanAttributeScrubbingRule rule : bundledSpi) {
            addIfNew(rules, seenNames, rule, RuleOrigin.BUNDLED_SPI);
        }

        for (SpanAttributeScrubbingRule rule : custom) {
            addIfNew(rules, seenNames, rule, RuleOrigin.EXTERNAL_EXTENSION);
        }

        return PriorityHardening.sortAndClampRuntimeRules(rules);
    }

    private static void addIfNew(List<RuntimeRule> rules, Set<String> seenNames,
                                 SpanAttributeScrubbingRule rule, RuleOrigin origin) {
        if (seenNames.add(rule.name())) {
            rules.add(new RuntimeRule(
                    new ExecutableRule(rule, new RuleCircuitBreaker(rule.name())),
                    origin
            ));
        } else {
            log.log(ScrubbingLogEvent.DUPLICATE_SCRUBBING_RULE_IGNORED,
                    fields -> fields.putString(AgentLogField.NAME, rule.name()));
        }
    }

    private static ExtensionRuleLoader.ValidationMode resolveValidationMode(ScrubbingExtensionConfig scrubbing) {
        return scrubbing.rulesValidationMode();
    }
}
