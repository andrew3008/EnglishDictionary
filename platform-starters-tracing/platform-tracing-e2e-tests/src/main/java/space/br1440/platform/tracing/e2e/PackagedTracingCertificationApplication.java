package space.br1440.platform.tracing.e2e;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

@SpringBootApplication
public class PackagedTracingCertificationApplication {

    static final String PROCESS_OK_TOKEN = "PACKAGED_PROCESS_OK";
    static final String LIFECYCLE_SENTINEL_STARTED_TOKEN = "PACKAGED_LIFECYCLE_SENTINEL_STARTED";
    static final String APPLICATION_READY_TOKEN = "PACKAGED_APPLICATION_READY";

    @Bean
    @Lazy(false)
    SmartLifecycle packagedLifecycleSentinel() {
        return new SmartLifecycle() {

            private volatile boolean running;

            @Override
            public void start() {
                running = true;
                System.out.println(LIFECYCLE_SENTINEL_STARTED_TOKEN);
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    void markApplicationReady() {
        System.out.println(APPLICATION_READY_TOKEN);
    }

    public static void main(String[] arguments) {
        PackagedConfigServerScenario configServerScenario = PackagedConfigServerScenario.fromSystemProperties();
        SpringApplication application = new SpringApplication(PackagedTracingCertificationApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.addInitializers(configServerScenario::initialize);

        try (ConfigurableApplicationContext context = application.run(arguments)) {
            configServerScenario.verify(context);
            if ("URL_MASKING".equals(System.getProperty("platformTracing.packagedScenario"))) {
                recordUrlFailure();
            }
        }

        System.out.println(PROCESS_OK_TOKEN);
    }

    private static void recordUrlFailure() {
        String message = "Request failed: https://packaged-user:packaged-password-canary@service.example/path"
                + "?token=packaged-query-canary&mode=debug#packaged-fragment-canary";
        Span span = GlobalOpenTelemetry.getTracer("packaged-url-masking")
                .spanBuilder("packaged-url-failure").startSpan();
        try {
            if (!span.isRecording()) {
                throw new IllegalStateException("Packaged URL masking scenario did not create a recording span");
            }

            span.addEvent("exception", Attributes.of(
                    AttributeKey.stringKey("exception.type"), IllegalStateException.class.getName(),
                    AttributeKey.stringKey("exception.message"), message));
            span.setStatus(StatusCode.ERROR, message);
        } finally {
            span.end();
        }
    }
}
