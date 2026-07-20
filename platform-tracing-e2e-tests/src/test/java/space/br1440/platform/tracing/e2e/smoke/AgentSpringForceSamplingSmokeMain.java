package space.br1440.platform.tracing.e2e.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;

import io.opentelemetry.api.OpenTelemetry;

import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Дочерний main для e2e smoke force-sampling: Spring Boot + embedded Tomcat + Agent spring-webmvc.
 * <p>
 * HTTP-запрос с {@code X-Trace-On} приходит из JUnit (OkHttp). После обработки — flush BSP и shutdown.
 * Контракт READY/args см. {@link AgentHttpSpringSmokeProcessRunner}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "space.br1440.platform.logging.configuration.LoggingAutoConfiguration",
        "space.br1440.platform.logging.configuration.GrpcLoggingConfiguration",
        "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration"
})
@Import(ProbeSmokeController.class)
public class AgentSpringForceSamplingSmokeMain {

    private static final String PLATFORM_AUTO_CONFIGURATIONS = String.join(",",
            "space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.SemanticLayerAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingAopAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingObservationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingActuatorAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.RequestContextSupplierAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.ServiceNameProviderAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.TracingRefreshScopeAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.async.TracingAsyncContextAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.kafka.PlatformKafkaOutboundAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.servlet.ServletTracingAutoConfiguration",
            "space.br1440.platform.tracing.autoconfigure.servlet.WebMvcSuppressMicrometerTracingAutoConfiguration");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: AgentSpringForceSamplingSmokeMain <port> <flushDelayMs>");
        }
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);

        SpringApplication application = new SpringApplication(AgentSpringForceSamplingSmokeMain.class);
        application.setRegisterShutdownHook(false);
        String suppressMicrometerTracing = System.getProperty(
                "platform.tracing.suppression.suppress-micrometer-tracing", "true");
        java.util.List<String> applicationArguments = new java.util.ArrayList<>(java.util.List.of(
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--platform.tracing.suppression.suppress-micrometer-tracing=" + suppressMicrometerTracing,
                "--management.tracing.enabled=true",
                "--logging.level.root=WARN"));
        if (Boolean.getBoolean("e2.stock.agent.baseline")) {
            // Контрольная ветка доказывает поведение stock Agent без platform starter composition.
            applicationArguments.add("--spring.autoconfigure.exclude=" + PLATFORM_AUTO_CONFIGURATIONS);
        }
        ConfigurableApplicationContext context = application.run(applicationArguments.toArray(String[]::new));

        CountDownLatch served = context.getBean(CountDownLatch.class);

        System.out.println("WEBMVC_E2:openTelemetryBeans=" + context.getBeansOfType(OpenTelemetry.class).size());
        System.out.println(AgentHttpSpringSmokeProcessRunner.READY_MARKER);
        System.out.flush();

        try {
            if (!served.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Внешний HTTP-запрос к /probe не получен за 60 с");
            }
            Thread.sleep(flushDelayMs);
            printSamplerDiagnostics();
        } finally {
            context.close();
        }
    }

    @Bean
    CountDownLatch servedLatch() {
        return new CountDownLatch(1);
    }

    private static void printSamplerDiagnostics() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(
                    "space.br1440.platform.tracing:type=SamplingControl,name=PlatformSamplingControl");
            System.out.println("SMOKE_PROPAGATORS=" + System.getProperty("otel.propagators"));
            System.out.println("SMOKE_SAMPLER=" + System.getProperty("otel.traces.sampler"));
            System.out.println("SMOKE_CAPTURE_HEADERS="
                    + System.getProperty("otel.instrumentation.http.server.capture-request-headers"));
            System.out.println("SMOKE_SAMPLING_RATIO=" + System.getProperty("platform.tracing.sampling.ratio"));
            System.out.println("SMOKE_SAMPLING_MBEAN_REGISTERED=" + server.isRegistered(name));
            if (server.isRegistered(name)) {
                System.out.println("SMOKE_SAMPLER_DECISIONS="
                        + server.getAttribute(name, "SamplerDecisionCounts"));
            }
        } catch (Exception e) {
            System.out.println("SMOKE_SAMPLER_DIAGNOSTICS_FAILED=" + e);
        }
        System.out.flush();
    }
}
