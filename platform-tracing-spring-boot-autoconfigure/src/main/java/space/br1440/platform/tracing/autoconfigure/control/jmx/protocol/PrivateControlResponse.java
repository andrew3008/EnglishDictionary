package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import javax.management.openmbean.CompositeData;

public record PrivateControlResponse(String outcome) {

    public static PrivateControlResponse decode(CompositeData response) {
        if (response == null || !PrivateControlWireTypes.RESPONSE.equals(response.getCompositeType())) {
            throw new IllegalArgumentException("Unexpected private control response CompositeType");
        }

        return new PrivateControlResponse((String) response.get("outcome"));
    }
}
