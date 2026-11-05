package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Value;

public final class ScrubbingValueTransformer {

    public static final String MASK = "***";
    public static final int MAX_ARRAY_ELEMENTS = 32;
    public static final int MAX_INPUT_CHARS = 4096;
    public static final String INPUT_LIMIT_REASON = "input-limit";
    public static final String IP_ADDRESS_REASON = "ip-address";

    private static final Result KEEP = new Result(ScrubbingAction.KEEP, null, Fallback.NONE);
    private static final Result DROP = new Result(ScrubbingAction.DROP, null, Fallback.NONE);
    private static final Result MASKED = new Result(ScrubbingAction.MASK, MASK, Fallback.NONE);

    private ScrubbingValueTransformer() {
    }

    public enum Fallback {
        NONE,
        MISSING_HMAC_KEY,
        INVALID_IP,
        INPUT_LIMIT,
        MALFORMED_INPUT
    }

    public record Result(ScrubbingAction action, @Nullable Object value, Fallback fallback) {

        @Override
        public String toString() {
            return "Result[action=" + action + ", fallback=" + fallback + ']';
        }
    }

    public static Result transform(AttributeType type, Object original, MergeOutcome outcome,
                                   @Nullable UnaryOperator<String> hash) {
        String masked = outcome.maskedValue();
        if (masked != null && type != AttributeType.STRING) {
            return outcome.decision().action() == ScrubbingAction.DROP
                    ? DROP : boundedMask(type, Fallback.MALFORMED_INPUT);
        }

        Result result = transform(type, masked != null ? masked : original, outcome.decision(), hash);
        if (result.action() == ScrubbingAction.KEEP && masked != null) {
            if (masked.length() > MAX_INPUT_CHARS) {
                return boundedMask(type, Fallback.INPUT_LIMIT);
            }

            if (!masked.equals(original)) {
                return new Result(ScrubbingAction.MASK, masked, result.fallback());
            }
        }

        return result;
    }

    public static Result transform(AttributeType type, Object value, ScrubbingDecision decision,
                                   @Nullable UnaryOperator<String> hash) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(decision, "decision");
        ScrubbingAction action = decision.action();
        if (action == ScrubbingAction.KEEP) {
            return KEEP;
        }

        if (action == ScrubbingAction.DROP) {
            return DROP;
        }

        if (action == ScrubbingAction.MASK && ScrubbingDecision.SCRUBBING_FAILED_REASON.equals(decision.reason())) {
            return new Result(ScrubbingAction.MASK, failClosedValue(type), Fallback.NONE);
        }

        if (action == ScrubbingAction.MASK && INPUT_LIMIT_REASON.equals(decision.reason())) {
            return boundedMask(type, Fallback.INPUT_LIMIT);
        }

        return switch (type) {
            case STRING -> string(value, decision, hash);
            case STRING_ARRAY -> strings(value, decision, hash);
            case LONG, DOUBLE, BOOLEAN -> numeric(type, value, action);
            case LONG_ARRAY, DOUBLE_ARRAY, BOOLEAN_ARRAY -> numbers(type, value, action);
            case VALUE -> DROP;
        };
    }

    public static Object failClosedValue(AttributeType type) {
        return sentinel(type, ScrubbingDecision.SCRUBBING_FAILED_REASON, 1);
    }

    public static Object dropValue(AttributeType type, Object value) {
        int count = 1;
        if (isArray(type) && value instanceof List<?> values) {
            int size = values.size();
            count = (size <= MAX_ARRAY_ELEMENTS) ? size : 1;
        }

        return sentinel(type, "", count);
    }

    private static Result string(Object value, ScrubbingDecision decision, @Nullable UnaryOperator<String> hash) {
        if (value == null) {
            return KEEP;
        }

        if (!(value instanceof String text)) {
            return boundedMask(AttributeType.STRING, Fallback.MALFORMED_INPUT);
        }

        if (text.length() > MAX_INPUT_CHARS) {
            return boundedMask(AttributeType.STRING, Fallback.INPUT_LIMIT);
        }

        return transformString(text, decision, hash);
    }

    private static Result transformString(String value, ScrubbingDecision decision, @Nullable UnaryOperator<String> hash) {
        return switch (decision.action()) {
            case MASK -> changed(value, MASK, ScrubbingAction.MASK, Fallback.NONE);
            case HASH -> hash(value, hash);
            case TRUNCATE -> truncate(value, decision);
            default -> throw new IllegalArgumentException("Unsupported string transformation action");
        };
    }

    private static Result hash(String value, @Nullable UnaryOperator<String> hash) {
        if (hash == null) {
            return changed(value, MASK, ScrubbingAction.MASK, Fallback.MISSING_HMAC_KEY);
        }

        String hashed = Objects.requireNonNull(hash.apply(value), "Hash result must not be null");
        return hashed.length() > MAX_INPUT_CHARS
                ? boundedMask(AttributeType.STRING, Fallback.INPUT_LIMIT)
                : changed(value, hashed, ScrubbingAction.HASH, Fallback.NONE);
    }

    private static Result truncate(String value, ScrubbingDecision decision) {
        if (IP_ADDRESS_REASON.equals(decision.reason())) {
            String truncated = IpPrefixTruncator.truncate(value);
            if (truncated == null) {
                return changed(value, MASK, ScrubbingAction.MASK, Fallback.INVALID_IP);
            }

            if (truncated.length() > MAX_INPUT_CHARS) {
                return boundedMask(AttributeType.STRING, Fallback.INPUT_LIMIT);
            }

            return changed(value, truncated, ScrubbingAction.TRUNCATE, Fallback.NONE);
        }

        int max = decision.maxLength();
        if (max < 0 || value.length() <= max) {
            return KEEP;
        }

        if (max > 0 && Character.isHighSurrogate(value.charAt(max - 1)) && Character.isLowSurrogate(value.charAt(max))) {
            max--;
        }

        return changed(value, value.substring(0, max), ScrubbingAction.TRUNCATE, Fallback.NONE);
    }

    private static Result strings(Object value, ScrubbingDecision decision, @Nullable UnaryOperator<String> hash) {
        if (!(value instanceof List<?> values)) {
            return boundedMask(AttributeType.STRING_ARRAY, Fallback.MALFORMED_INPUT);
        }

        int size = values.size();
        if (size > MAX_ARRAY_ELEMENTS) {
            return boundedMask(AttributeType.STRING_ARRAY, Fallback.INPUT_LIMIT);
        }

        List<String> transformed = new ArrayList<>(size);
        int remaining = MAX_INPUT_CHARS;
        for (int i = 0; i < size; i++) {
            Object element = values.get(i);
            if (element == null) {
                transformed.add(null);
            } else if (element instanceof String text) {
                if (text.length() > remaining) {
                    return boundedMask(AttributeType.STRING_ARRAY, Fallback.INPUT_LIMIT);
                }

                remaining -= text.length();
                transformed.add(text);
            } else {
                return boundedMask(AttributeType.STRING_ARRAY, Fallback.MALFORMED_INPUT);
            }
        }

        ScrubbingAction applied = ScrubbingAction.KEEP;
        Fallback fallback = Fallback.NONE;
        int outputRemaining = MAX_INPUT_CHARS;
        for (int i = 0; i < size; i++) {
            String element = transformed.get(i);
            if (element == null) {
                continue;
            }

            Result result = transformString(element, decision, hash);
            if (result.fallback() != Fallback.NONE) {
                fallback = result.fallback();
            }

            if (result.action() != ScrubbingAction.KEEP) {
                transformed.set(i, (String) result.value());
                applied = result.action() == ScrubbingAction.MASK || applied == ScrubbingAction.MASK
                        ? ScrubbingAction.MASK : result.action();
            }

            String output = transformed.get(i);
            if (output.length() > outputRemaining) {
                return boundedMask(AttributeType.STRING_ARRAY, Fallback.INPUT_LIMIT);
            }

            outputRemaining -= output.length();
        }

        return applied == ScrubbingAction.KEEP
                ? new Result(ScrubbingAction.KEEP, null, fallback)
                : new Result(applied, Collections.unmodifiableList(transformed), fallback);
    }

    private static Result numeric(AttributeType type, Object value, ScrubbingAction action) {
        if (action == ScrubbingAction.TRUNCATE) {
            return KEEP;
        }

        boolean valid = switch (type) {
            case LONG -> value instanceof Long;
            case DOUBLE -> value instanceof Double;
            case BOOLEAN -> value instanceof Boolean;
            default -> false;
        };

        if (!valid) {
            return boundedMask(type, Fallback.MALFORMED_INPUT);
        }

        return changed(value, sentinel(type, MASK, 1), ScrubbingAction.MASK, Fallback.NONE);
    }

    private static Result numbers(AttributeType type, Object value, ScrubbingAction action) {
        if (action == ScrubbingAction.TRUNCATE) {
            return KEEP;
        }

        if (!(value instanceof List<?> values)) {
            return boundedMask(type, Fallback.MALFORMED_INPUT);
        }

        int size = values.size();
        if (size > MAX_ARRAY_ELEMENTS) {
            return boundedMask(type, Fallback.INPUT_LIMIT);
        }

        Object zero = switch (type) {
            case LONG_ARRAY -> 0L;
            case DOUBLE_ARRAY -> 0.0d;
            case BOOLEAN_ARRAY -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("Unsupported numeric array type");
        };

        boolean changed = false;
        for (int i = 0; i < size; i++) {
            Object element = values.get(i);
            if (element != null && !zero.getClass().isInstance(element)) {
                return boundedMask(type, Fallback.MALFORMED_INPUT);
            }
            changed |= !zero.equals(element);
        }

        return changed ? new Result(ScrubbingAction.MASK, sentinel(type, MASK, size), Fallback.NONE) : KEEP;
    }

    private static Result changed(Object original, Object replacement, ScrubbingAction action, Fallback fallback) {
        if (Objects.equals(original, replacement)) {
            return fallback == Fallback.NONE ? KEEP : new Result(ScrubbingAction.KEEP, null, fallback);
        }

        if (action == ScrubbingAction.MASK && fallback == Fallback.NONE && MASK.equals(replacement)) {
            return MASKED;
        }

        return new Result(action, replacement, fallback);
    }

    private static Result boundedMask(AttributeType type, Fallback fallback) {
        return new Result(ScrubbingAction.MASK, sentinel(type, MASK, 1), fallback);
    }

    private static Object sentinel(AttributeType type, String text, int count) {
        return switch (type) {
            case STRING -> text;
            case STRING_ARRAY -> Collections.nCopies(count, text);
            case LONG -> 0L;
            case DOUBLE -> 0.0d;
            case BOOLEAN -> Boolean.FALSE;
            case LONG_ARRAY -> Collections.nCopies(count, 0L);
            case DOUBLE_ARRAY -> Collections.nCopies(count, 0.0d);
            case BOOLEAN_ARRAY -> Collections.nCopies(count, Boolean.FALSE);
            case VALUE -> Value.of(text);
        };
    }

    private static boolean isArray(AttributeType type) {
        return switch (type) {
            case STRING_ARRAY, LONG_ARRAY, DOUBLE_ARRAY, BOOLEAN_ARRAY -> true;
            default -> false;
        };
    }
}
