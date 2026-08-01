package space.br1440.platform.tracing.autoconfigure.status;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * Единая read-only Actuator-поверхность состояния платформенной трассировки.
 */
@Endpoint(id = "tracingStatus")
public final class TracingStatusEndpoint {

    private final TracingStatusAssembler assembler;

    public TracingStatusEndpoint(TracingStatusAssembler assembler) {
        this.assembler = assembler;
    }

    @ReadOperation
    public TracingStatusSnapshot tracingStatus() {
        return assembler.assemble();
    }
}
