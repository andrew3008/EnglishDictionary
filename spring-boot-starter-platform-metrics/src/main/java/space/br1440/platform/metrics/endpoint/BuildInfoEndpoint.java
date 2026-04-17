package space.br1440.platform.metrics.endpoint;


import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;

@WebEndpoint(id = "platform")
public class BuildInfoEndpoint {

    private final BuildInfoMetric buildInfoMetric;

    public BuildInfoEndpoint(BuildInfoMetric buildInfoMetric) {
        this.buildInfoMetric = buildInfoMetric;
    }

    @ReadOperation
    public synchronized BuildInfo getBuildInfo() {
        return buildInfoMetric.getBuildInfo();
    }
}
