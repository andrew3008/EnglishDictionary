package space.br1440.platform.metrics.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Имена gRPC server-метрик платформы (префикс {@value #PREFIX}).
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = GrpcMetricsProperties.PREFIX)
public class GrpcMetricsProperties {

    public static final String PREFIX = "platform.metrics.grpc";

    private String timerMetricName;
    private String counterSentMetricName;
    private String counterReceivedMetricName;
    private String streamMessagesSentMetricName;
    private String streamMessagesReceivedMetricName;
}
