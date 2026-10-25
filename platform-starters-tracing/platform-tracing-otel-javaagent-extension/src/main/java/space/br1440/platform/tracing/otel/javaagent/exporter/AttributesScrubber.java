package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import space.br1440.platform.common.util.MapUtils;
import space.br1440.platform.common.util.SetUtils;
import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.IpPrefixTruncator;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingKeyNormalizer;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.Value;
import lombok.experimental.UtilityClass;

@UtilityClass
final class AttributesScrubber {

    private static final String MASK = "***";
    private static final long SENTINEL_LONG = 0L;
    private static final double SENTINEL_DOUBLE = 0.0d;
    private static final boolean SENTINEL_BOOLEAN = Boolean.FALSE;
    private static final Value<String> VALUE_SENTINEL = Value.of(ScrubbingDecision.SCRUBBING_FAILED_REASON);

    record Result(Attributes attributes, boolean changed, boolean criticalFailure) {
    }

    static Result scrub(Attributes attributes, ScrubbingRuntime runtime) {
        List<ExecutableRule> rules = runtime.attributeExecutableRules();
        if (attributes.isEmpty()) {
            return new Result(attributes, false, false);
        }

        if (rules.isEmpty()) {
            return removeValueAttributeTrees(attributes, runtime);
        }

        ScrubPass pass = new ScrubPass(runtime, rules, attributes);
        attributes.forEach(pass::visit);

        if (!pass.changed()) {
            return new Result(attributes, false, pass.criticalFailure());
        }

        return new Result(pass.rebuild(), true, pass.criticalFailure());
    }

    static Result removeValueAttributeTrees(Attributes attributes, ScrubbingRuntime runtime) {
        if (attributes.isEmpty()) {
            return new Result(attributes, false, false);
        }

        Set<String> valueAttributeNames = valueAttributeNames(attributes);
        if (valueAttributeNames.isEmpty()) {
            return new Result(attributes, false, false);
        }

        AttributesBuilder builder = Attributes.builder();
        long removed = 0L;
        for (Map.Entry<AttributeKey<?>, Object> entry : attributes.asMap().entrySet()) {
            AttributeKey<?> key = entry.getKey();
            if (valueAttributeNames.contains(key.getKey())) {
                removed++;
            } else {
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
        attributes.forEach((key, ignored) -> writeSentinel(builder, key));
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
            MergeOutcome outcome = MergeEngine.evaluateDetailed(rules, normalizedKey, value);
            recordOutcome(outcome);

            ScrubbingDecision decision = outcome.decision();
            ScrubbingAction action = decision.action();
            if (action == ScrubbingAction.KEEP) {
                return;
            }

            AttributeType type = key.getType();
            if (action == ScrubbingAction.DROP) {
                drop(key);
                return;
            }

            if (action == ScrubbingAction.TRUNCATE && isNumericOrBoolean(type)) {
                return;
            }

            if (type == AttributeType.STRING) {
                String transformed = transformString((String) value, decision, runtime);
                if (Objects.equals(transformed, value)) {
                    return;
                }

                replace(key, transformed);
                return;
            }

            Object replacement = nonStringReplacement(key, value, decision, runtime);
            if (replacement != null) {
                replace(key, replacement);
            } else {
                drop(key);
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
                criticalFailure = true;
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

    private static boolean isNumericOrBoolean(AttributeType type) {
        return switch (type) {
            case LONG, DOUBLE, BOOLEAN, LONG_ARRAY, DOUBLE_ARRAY, BOOLEAN_ARRAY -> true;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private static Object nonStringReplacement(AttributeKey<?> key, Object value,
                                                ScrubbingDecision decision, ScrubbingRuntime runtime) {
        AttributeType type = key.getType();
        return switch (type) {
            case STRING_ARRAY -> {
                List<String> in = (List<String>) value;
                yield in.stream().map(v -> transformString(v, decision, runtime)).toList();
            }
            case LONG -> SENTINEL_LONG;
            case DOUBLE -> SENTINEL_DOUBLE;
            case BOOLEAN -> SENTINEL_BOOLEAN;
            case LONG_ARRAY -> Collections.nCopies(((List<?>) value).size(), SENTINEL_LONG);
            case DOUBLE_ARRAY -> Collections.nCopies(((List<?>) value).size(), SENTINEL_DOUBLE);
            case BOOLEAN_ARRAY -> Collections.nCopies(((List<?>) value).size(), SENTINEL_BOOLEAN);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static void putTyped(AttributesBuilder builder, AttributeKey<?> key, Object value) {
        switch (key.getType()) {
            case STRING -> builder.put((AttributeKey<String>) key, (String) value);
            case STRING_ARRAY, LONG_ARRAY, DOUBLE_ARRAY, BOOLEAN_ARRAY ->
                    builder.put((AttributeKey<List<String>>) key, (List<String>) value);
            case LONG -> builder.put((AttributeKey<Long>) key, (Long) value);
            case DOUBLE -> builder.put((AttributeKey<Double>) key, (Double) value);
            case BOOLEAN -> builder.put((AttributeKey<Boolean>) key, (Boolean) value);
            case VALUE -> builder.put((AttributeKey<Value<?>>) key, (Value<?>) value);
            default -> {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeSentinel(AttributesBuilder builder, AttributeKey<?> key) {
        switch (key.getType()) {
            case STRING -> builder.put((AttributeKey<String>) key, ScrubbingDecision.SCRUBBING_FAILED_REASON);
            case STRING_ARRAY -> builder.put((AttributeKey<List<String>>) key, List.of(ScrubbingDecision.SCRUBBING_FAILED_REASON));
            case LONG -> builder.put((AttributeKey<Long>) key, SENTINEL_LONG);
            case DOUBLE -> builder.put((AttributeKey<Double>) key, SENTINEL_DOUBLE);
            case BOOLEAN -> builder.put((AttributeKey<Boolean>) key, SENTINEL_BOOLEAN);
            case LONG_ARRAY -> builder.put((AttributeKey<List<Long>>) key, List.of(SENTINEL_LONG));
            case DOUBLE_ARRAY -> builder.put((AttributeKey<List<Double>>) key, List.of(SENTINEL_DOUBLE));
            case BOOLEAN_ARRAY -> builder.put((AttributeKey<List<Boolean>>) key, List.of(SENTINEL_BOOLEAN));
            case VALUE -> builder.put((AttributeKey<Value<?>>) key, VALUE_SENTINEL);

            default -> {
            }
        }
    }

    private static String transformString(String value, ScrubbingDecision decision, ScrubbingRuntime runtime) {
        if (value == null) {
            return null;
        }

        return switch (decision.action()) {
            case MASK -> MASK;
            case DROP -> "";
            case HASH -> runtime.hasHmacKey() ? runtime.hmacHex(value) : MASK;
            case TRUNCATE -> transformTruncate(value, decision);
            default -> value;
        };
    }

    private static String transformTruncate(String value, ScrubbingDecision decision) {
        if (BuiltInSpanAttributeScrubbingRules.IP_ADDRESS.configName().equals(decision.reason())) {
            String truncated = IpPrefixTruncator.truncate(value);
            return (truncated != null) ? truncated : MASK;
        }

        int max = decision.maxLength();
        return (max >= 0 && value.length() > max) ? value.substring(0, max) : value;
    }
}
