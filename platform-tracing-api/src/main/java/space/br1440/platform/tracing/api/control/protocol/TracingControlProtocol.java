package space.br1440.platform.tracing.api.control.protocol;

import lombok.RequiredArgsConstructor;

import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@RequiredArgsConstructor(access = PRIVATE)
public final class TracingControlProtocol {

    private static final int CURRENT_MAJOR = 1;
    private static final TracingControlProtocolVersion VERSION_V1 = new TracingControlProtocolVersion(CURRENT_MAJOR);
    private static final TracingControlProtocolSchema SCHEMA_V1 = TracingControlProtocolSchema.v1();
    private static final TracingControlProtocolDecoder DECODER_V1 = new TracingControlProtocolDecoder(CURRENT_MAJOR, SCHEMA_V1);

    private static final TracingControlProtocol INSTANCE = new TracingControlProtocol(VERSION_V1, DECODER_V1);

    private final TracingControlProtocolVersion version;
    private final TracingControlProtocolDecoder decoder;

    public static TracingControlProtocol current() {
        return INSTANCE;
    }

    public TracingControlProtocolVersion version() {
        return version;
    }

    public TracingControlProtocolDecodeResult decode(Map<String, Object> payload) {
        return decoder.decode(payload);
    }
}
