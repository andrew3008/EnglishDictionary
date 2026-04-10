package space.br1440.platform.metrics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform-metric.http")
public class HttpMetricProperties {

    private PathMode pathMode = PathMode.NORMALIZED;

    public PathMode getPathMode() {
        return pathMode;
    }

    public void setPathMode(PathMode pathMode) {
        this.pathMode = pathMode;
    }

    public enum PathMode {
        NORMALIZED,
        RAW
    }
}
