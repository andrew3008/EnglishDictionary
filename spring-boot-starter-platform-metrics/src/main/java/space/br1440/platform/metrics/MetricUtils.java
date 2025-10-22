package space.br1440.platform.metrics;


import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;

import java.time.Duration;
import java.util.Map;

public class MetricUtils {

    private MetricUtils(){}

    public static Duration[] createSla(long... sla) {
        Duration[] result = new Duration[sla.length];
        for (int i = 0; i < sla.length; i++) {
            result[i] = Duration.ofMillis(sla[i]);
        }
        return result;
    }

    public static String getClientIdForKafka(Map<MetricName, ? extends Metric> metricMap) {
        String clientId = "unknown";
        for (Map.Entry<MetricName, ? extends Metric> entry : metricMap.entrySet()) {
            Metric metric = entry.getValue();
            clientId = metric.metricName().tags().get("client-id");
            if (!clientId.equals("unknown")) {
                break;
            }
        }
        return clientId;
    }




}
