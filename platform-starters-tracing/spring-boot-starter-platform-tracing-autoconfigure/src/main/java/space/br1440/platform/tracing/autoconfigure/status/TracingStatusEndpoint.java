package space.br1440.platform.tracing.autoconfigure.status;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import lombok.RequiredArgsConstructor;

@Endpoint(id = "tracingStatus")
@RequiredArgsConstructor
public final class TracingStatusEndpoint {

    private final TracingStatusAssembler assembler;

    @ReadOperation
    public TracingStatusSnapshot tracingStatus() {
        return assembler.assemble();
    }
}
