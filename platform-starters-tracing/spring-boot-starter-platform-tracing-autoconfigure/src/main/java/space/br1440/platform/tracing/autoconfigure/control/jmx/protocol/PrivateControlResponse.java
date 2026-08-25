package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlWireTypes.WireField;

public record PrivateControlResponse(String outcome) {

    private static final String OPERATION = "applySnapshot";

    public static PrivateControlResponse decode(CompositeData response) {
        if (response == null) {
            throw invalid("expected CompositeData, got null");
        }

        if (!PrivateControlWireTypes.RESPONSE.equals(response.getCompositeType())) {
            throw invalid("unexpected CompositeType " + response.getCompositeType().getTypeName()
                    + "; expected " + PrivateControlWireTypes.RESPONSE.getTypeName());
        }

        Object value = response.get(WireField.OUTCOME.wireName());
        if (value == null) {
            throw invalid("field '" + WireField.OUTCOME.wireName() + "' is null");
        }

        if (!(value instanceof String outcome)) {
            throw invalid("field '" + WireField.OUTCOME.wireName() + "' has unexpected type " + value.getClass().getName());
        }

        if (outcome.isBlank()) {
            throw invalid(WireField.OUTCOME.wireName() + " must not be blank");
        }

        return new PrivateControlResponse(outcome);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(OPERATION + ": " + reason);
    }
}
