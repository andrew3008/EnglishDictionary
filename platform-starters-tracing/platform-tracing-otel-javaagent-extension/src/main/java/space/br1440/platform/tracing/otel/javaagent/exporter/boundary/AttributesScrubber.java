package space.br1440.platform.tracing.otel.javaagent.exporter.boundary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.common.util.MapUtils;
import space.br1440.platform.common.util.SetUtils;
import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingKeyNormalizer;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingValueTransformer;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.Value;
import lombok.experimental.UtilityClass;

@UtilityClass
final class AttributesScrubber {

    private static final Value<String> VALUE_SENTINEL = Value.of(ScrubbingDecision.SCRUBBING_FAILED_REASON);

    record Result(Attributes attributes, boolean changed, boolean criticalFailure) {
    }

    static Result scrub(Attributes attributes, ScrubbingRuntime runtime) {
        List<ExecutableRule> rules = runtime.attributeExecutableRules();
        if (attributes.isEmpty()) {
            return new Result(attributes, false, false);
        }

        if (rules.isEmpty()) {
            return removeUnpublishableAttributes(attributes, runtime);
        }

        ScrubPass pass = new ScrubPass(runtime, rules, attributes);
        attributes.forEach(pass::visit);

        if (!pass.changed()) {
            return new Result(attributes, false, pass.criticalFailure());
        }

        return new Result(pass.rebuild(), true, pass.criticalFailure());
    }

    static Result removeUnpublishableAttributes(Attributes attributes, ScrubbingRuntime runtime) {
        if (attributes.isEmpty()) {
            return new Result(attributes, false, false);
        }

        Set<String> valueAttributeNames = valueAttributeNames(attributes);
        boolean hasRejectedDiagnostic = attributes.asMap().entrySet().stream()
                .anyMatch(entry ->
                        !runtime.diagnosticAttributePermitted(
                                ScrubbingKeyNormalizer.normalize(entry.getKey().getKey()), entry.getValue()
                        )
                );
        if (valueAttributeNames.isEmpty() && !hasRejectedDiagnostic) {
            return new Result(attributes, false, false);
        }

        AttributesBuilder builder = Attributes.builder();
        long removed = 0L;
        for (Map.Entry<AttributeKey<?>, Object> entry : attributes.asMap().entrySet()) {
            AttributeKey<?> key = entry.getKey();
            if (valueAttributeNames.contains(key.getKey())) {
                removed++;
            } else if (runtime.diagnosticAttributePermitted(
                    ScrubbingKeyNormalizer.normalize(key.getKey()), entry.getValue())
            ) {
                putTyped(builder, key, entry.getValue());
            }
        }

        runtime.recordBoundaryValueAttributesRemoved(removed);
        return new Result(builder.build(), true, false);
    }

    static Attributes collapseToSentinel(Attributes attributes) {
        if (attributes.isEmpty()) {
            return attributes;
        }

        AttributesBuilder builder = Attributes.builder();
        attributes.forEach((key, ignored) -> {
            if (!ScrubbingRuntime.isDiagnosticAttribute(ScrubbingKeyNormalizer.normalize(key.getKey()))) {
                Object value = (key.getType() == AttributeType.VALUE)
                        ? VALUE_SENTINEL
                        : ScrubbingValueTransformer.failClosedValue(key.getType());

                putTyped(builder, key, value);
            }
        });
        return builder.build();
    }

    private static final class ScrubPass {

        private final ScrubbingRuntime runtime;
        private final List<ExecutableRule> rules;
        private final Attributes source;
        private final Set<String> valueAttributeNames;

        private Map<AttributeKey<?>, Object> replacements;
        private Set<AttributeKey<?>> drops;
        private boolean criticalFailure;

        private ScrubPass(ScrubbingRuntime runtime, List<ExecutableRule> rules, Attributes source) {
            this.runtime = runtime;
            this.rules = rules;
            this.source = source;
            this.valueAttributeNames = valueAttributeNames(source);
        }

        private void visit(AttributeKey<?> key, Object value) {
            if (valueAttributeNames.contains(key.getKey())) {
                drop(key);
                runtime.recordBoundaryValueAttributesRemoved(1L);
                return;
            }

            String normalizedKey = ScrubbingKeyNormalizer.normalize(key.getKey());
            if (!runtime.diagnosticAttributePermitted(normalizedKey, value)) {
                drop(key);
                return;
            }
            MergeOutcome outcome = MergeEngine.evaluateDetailed(rules, normalizedKey, value, key.getType());
            recordOutcome(outcome);

            ScrubbingValueTransformer.Result result = runtime.transformAttribute(key.getType(), value, outcome);
            if (result.action() == ScrubbingAction.KEEP) {
                return;
            }

            if (result.action() == ScrubbingAction.DROP
                    || !runtime.diagnosticAttributePermitted(normalizedKey, result.value())) {
                drop(key);
            } else {
                replace(key, result.value());
            }
        }

        private void recordOutcome(MergeOutcome outcome) {
            if (outcome.skippedCircuitOpen() > 0L) {
                runtime.recordBoundarySkippedCircuitOpen(outcome.skippedCircuitOpen());
            }

            if (outcome.nonCriticalFailures() > 0L) {
                runtime.recordBoundaryNonCriticalFailures(outcome.nonCriticalFailures());
            }

            if (outcome.criticalFailures() > 0L) {
                runtime.recordBoundaryCriticalFailures(outcome.criticalFailures());
                criticalFailure |= outcome.requiresBoundaryCollapse();
            }
        }

        private void drop(AttributeKey<?> key) {
            if (drops == null) {
                drops = new HashSet<>();
            }
            drops.add(key);
        }

        private void replace(AttributeKey<?> key, Object replacementValue) {
            if (replacements == null) {
                replacements = new HashMap<>();
            }
            replacements.put(key, replacementValue);
        }

        private boolean changed() {
            return !MapUtils.isNullOrEmpty(replacements) || !SetUtils.isNullOrEmpty(drops);
        }

        private boolean criticalFailure() {
            return criticalFailure;
        }

        private Attributes rebuild() {
            Set<AttributeKey<?>> droppedKeys = (drops == null) ? Set.of() : drops;
            Map<AttributeKey<?>, Object> replacedValues = (replacements == null) ? Map.of() : replacements;

            AttributesBuilder out = Attributes.builder();
            source.forEach((key, value) -> {
                if (droppedKeys.contains(key)) {
                    return;
                }

                Object replacementValue = replacedValues.get(key);
                putTyped(out, key, replacementValue != null ? replacementValue : value);
            });
            return out.build();
        }
    }

    private static Set<String> valueAttributeNames(Attributes attributes) {
        Set<String> names = new HashSet<>();
        attributes.forEach((key, ignored) -> {
            if (key.getType() == AttributeType.VALUE) {
                names.add(key.getKey());
            }
        });
        return names;
    }

    @SuppressWarnings("unchecked")
    private static void putTyped(AttributesBuilder builder, AttributeKey<?> key, Object value) {
        builder.put((AttributeKey<Object>) key, value);
    }
}
