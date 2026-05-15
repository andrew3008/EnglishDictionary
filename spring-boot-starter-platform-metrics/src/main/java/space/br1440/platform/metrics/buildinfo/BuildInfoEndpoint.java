package space.br1440.platform.metrics.buildinfo;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;

@WebEndpoint(id = "platform")
@RequiredArgsConstructor
public class BuildInfoEndpoint {

    private final BuildInfoMetric buildInfoMetric;

    @ReadOperation
    @SuppressWarnings("unused")
    public synchronized BuildInfo getBuildInfo() {
        return buildInfoMetric.getBuildInfo();
    }
}
