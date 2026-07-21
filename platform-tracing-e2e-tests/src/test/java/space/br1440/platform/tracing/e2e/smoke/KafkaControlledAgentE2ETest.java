package space.br1440.platform.tracing.e2e.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;

@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true")
class KafkaControlledAgentE2ETest {

    private static final String SERVICE_NAME = "e2-kafka-controlled-agent";
    private static GenericContainer<?> jaeger;
    private static KafkaContainer kafka;
    private static JaegerV3QueryClient jaegerClient;

    @BeforeAll
    static void startInfrastructure() {
        jaeger = JaegerTestContainerSupport.newJaeger();
        jaeger.start();
        jaegerClient = new JaegerV3QueryClient(JaegerTestContainerSupport.queryBaseUrl(jaeger));
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));
        kafka.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (kafka != null) {
            kafka.stop();
        }
        if (jaeger != null) {
            jaeger.stop();
        }
    }

    @Test
    void producerSingleRetryAndBatchLinksWorkWithoutSpringSdkBean() throws Exception {
        String agent = System.getProperty("smoke.controlled.agent.jar");
        String classpath = System.getProperty("smoke.test.runtime.classpath");
        assertThat(new File(agent)).isFile();

        String suffix = UUID.randomUUID().toString();
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dotel.service.name=" + SERVICE_NAME);
        command.add("-Dotel.traces.exporter=otlp");
        command.add("-Dotel.exporter.otlp.endpoint=" + JaegerTestContainerSupport.otlpHttpEndpoint(jaeger));
        command.add("-Dotel.exporter.otlp.protocol=http/protobuf");
        command.add("-Dotel.traces.sampler=platform");
        command.add("-Dplatform.tracing.sampling.ratio=1");
        command.add("-Dplatform.tracing.queue.overflow-policy=UPSTREAM");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dotel.bsp.schedule.delay=200");
        command.add("-javaagent:" + agent);
        command.add("-cp");
        command.add(classpath);
        command.add(KafkaAgentParitySmokeMain.class.getName());
        command.add(kafka.getBootstrapServers());
        command.add("e2-single-" + suffix);
        command.add("e2-batch-" + suffix);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }
        assertThat(process.waitFor(3, TimeUnit.MINUTES)).isTrue();
        assertThat(process.exitValue()).as(output.toString()).isZero();
        assertThat(output.toString())
                .contains("KAFKA_E2:singleAttempts=2")
                .contains("KAFKA_E2:batchRecords=2")
                .contains("KAFKA_E2:singleRequestIdStable=true")
                .contains("KAFKA_E2:spoofedCorrelationRejected=true")
                .contains("KAFKA_E2:localCorrelationVisible=true")
                .contains("KAFKA_E2:batchRequestIdsDistinct=true")
                .contains("KAFKA_E2:batchCurrentIdentityEmpty=true")
                .contains("KAFKA_E2:openTelemetryBeans=0")
                .contains("KAFKA_E2:samplerDecisions=")
                .contains("KAFKA_E2:COMPLETE")
                .doesNotContain("OpenTelemetry Javaagent failed to start");

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            List<String> names = jaegerClient.listSpanNames(SERVICE_NAME);
            System.out.println("KAFKA_E2:spanNames=" + names);
            assertThat(names).hasSize(7);
            assertThat(names.stream().filter(name -> name.contains("single") && name.contains("publish"))).hasSize(1);
            assertThat(names.stream().filter(name -> name.contains("batch") && name.contains("publish"))).hasSize(2);
            assertThat(names.stream().filter(name -> name.contains("single") && name.contains("process"))).hasSize(2);
            assertThat(names.stream().filter(name -> name.contains("batch") && name.contains("process"))).hasSize(2);
            assertThat(jaegerClient.maximumSpanLinkCount(SERVICE_NAME)).isGreaterThanOrEqualTo(2);
            assertThat(jaegerClient.maximumDistinctSpanLinkTraceCount(SERVICE_NAME)).isGreaterThanOrEqualTo(2);
        });
    }
}
