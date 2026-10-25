package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.agent.AgentInstanceIdValidator;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public record PlatformJmxResponse(long acceptedSequence,
                                  String attemptId,
                                  MessageCode messageCode,
                                  Outcome outcome,
                                  String agentInstanceId,
                                  List<MessageCode> violationCodes) {

    private static final String OPERATION = JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT;

    private static final String FIELD_ACCEPTED_SEQUENCE = JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE;
    private static final String FIELD_ATTEMPT_ID = JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ATTEMPT_ID;
    private static final String FIELD_MESSAGE_CODE = JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE;
    private static final String FIELD_OUTCOME = JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME;
    private static final String FIELD_AGENT_INSTANCE_ID = JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_AGENT_INSTANCE_ID;
    private static final String FIELD_VIOLATION_CODES = JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_VIOLATION_CODES;

    public enum Outcome {
        APPLIED,
        UNCHANGED,
        REJECTED,
        STALE,
        FAILED;

        boolean isSuccessful() {
            return (this == APPLIED) || (this == UNCHANGED);
        }
    }

    public enum MessageCode {
        NONE,
        MISSING_REQUIRED_FIELD,
        TYPE_MISMATCH,
        INVALID_VALUE,
        INVALID_SAMPLING_RATIO,
        INVALID_ROUTE_RATIO,
        INVALID_VALIDATION_LEVEL,
        INVALID_DIAGNOSTICS_LOG_LEVEL,
        LIMIT_EXCEEDED,
        AGENT_INSTANCE_MISMATCH,
        ACCEPTED_SEQUENCE_MISMATCH,
        INTERNAL_FAILURE
    }

    public PlatformJmxResponse {
        violationCodes = List.copyOf(violationCodes);
        agentInstanceId = AgentInstanceIdValidator.requireCanonical(agentInstanceId, FIELD_AGENT_INSTANCE_ID);

        requireNonNegative(acceptedSequence);
        requireNoDuplicates(violationCodes);
        requireConsistentEvidence(outcome, messageCode, violationCodes);
    }

    public static PlatformJmxResponse decode(CompositeData response) {
        requireCompositeType(response);

        return new PlatformJmxResponse(
                value(response, FIELD_ACCEPTED_SEQUENCE, Long.class),
                token(response, FIELD_ATTEMPT_ID),
                parseEnum(MessageCode.class, FIELD_MESSAGE_CODE, token(response, FIELD_MESSAGE_CODE)),
                parseEnum(Outcome.class, FIELD_OUTCOME, token(response, FIELD_OUTCOME)),
                token(response, FIELD_AGENT_INSTANCE_ID),
                parseViolationCodes(value(response, FIELD_VIOLATION_CODES, String[].class))
        );
    }

    public void validateCorrelation(String expectedAgentInstanceId,
                                    long expectedAcceptedSequence,
                                    String expectedAttemptId) {
        requireMatchingAttempt(expectedAttemptId);

        if (outcome.isSuccessful()) {
            requireMatchingAgentInstance(expectedAgentInstanceId);
            requireExpectedSequenceProgression(expectedAcceptedSequence);
        }
    }

    private static void requireNonNegative(long acceptedSequence) {
        if (acceptedSequence < 0L) {
            throw invalid("acceptedSequence must not be negative");
        }
    }

    private static void requireNoDuplicates(List<MessageCode> violationCodes) {
        if (Set.copyOf(violationCodes).size() != violationCodes.size()) {
            throw invalid("violationCodes contain duplicates");
        }
    }

    private static void requireConsistentEvidence(Outcome outcome,
                                                  MessageCode messageCode,
                                                  List<MessageCode> violationCodes) {
        boolean successful = outcome.isSuccessful();
        boolean hasFailureEvidence = (messageCode != MessageCode.NONE) || !violationCodes.isEmpty();

        if (successful && hasFailureEvidence) {
            throw invalid("successful outcome contains failure evidence");
        }

        if (!successful && (messageCode == MessageCode.NONE || !violationCodes.equals(List.of(messageCode)))) {
            throw invalid("failed outcome does not contain exact failure evidence");
        }
    }

    private static void requireCompositeType(CompositeData response) {
        if (response == null) {
            throw invalid("expected CompositeData, got null");
        }

        if (!JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE.equals(response.getCompositeType())) {
            String message = "unexpected CompositeType %s; expected %s"
                    .formatted(
                            response.getCompositeType().getTypeName(),
                            JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE.getTypeName()
                    );
            throw invalid(message);
        }
    }

    private static List<MessageCode> parseViolationCodes(String[] rawViolationCodes) {
        return Arrays.stream(rawViolationCodes)
                .map(code -> parseEnum(MessageCode.class, FIELD_VIOLATION_CODES, code))
                .toList();
    }

    private static String token(CompositeData response, String field) {
        String raw = value(response, field, String.class);
        if (raw.isBlank() || !raw.equals(raw.trim())) {
            throw invalid("field '" + field + "' must be a non-blank trimmed token");
        }
        return raw;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String field, String rawValue) {
        try {
            return Enum.valueOf(type, rawValue);
        } catch (IllegalArgumentException e) {
            throw invalid("field '" + field + "' has unknown value '" + rawValue + "'");
        }
    }

    private static <T> T value(CompositeData response, String field, Class<T> type) {
        Object raw = response.get(field);
        if (raw == null) {
            throw invalid("field '" + field + "' is null");
        }

        if (!type.isInstance(raw)) {
            throw invalid("field '" + field + "' has unexpected type " + raw.getClass().getName());
        }

        return type.cast(raw);
    }

    private void requireMatchingAttempt(String expectedAttemptId) {
        if (!attemptId.equals(expectedAttemptId)) {
            throw invalid("attemptId does not match the request");
        }
    }

    private void requireMatchingAgentInstance(String expectedAgentInstanceId) {
        if (!agentInstanceId.equals(expectedAgentInstanceId)) {
            throw invalid("successful response agentInstanceId does not match the request");
        }
    }

    private void requireExpectedSequenceProgression(long expectedAcceptedSequence) {
        switch (outcome) {
            case APPLIED -> {
                if (expectedAcceptedSequence == Long.MAX_VALUE || acceptedSequence != expectedAcceptedSequence + 1L) {
                    throw invalid("APPLIED acceptedSequence is not the next revision");
                }
            }

            case UNCHANGED -> {
                if (acceptedSequence != expectedAcceptedSequence) {
                    throw invalid("UNCHANGED acceptedSequence does not match the request");
                }
            }

            default -> throw new IllegalStateException("unreachable: requireExpectedSequenceProgression is only called for a successful outcome");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(OPERATION + ": " + reason);
    }
}
