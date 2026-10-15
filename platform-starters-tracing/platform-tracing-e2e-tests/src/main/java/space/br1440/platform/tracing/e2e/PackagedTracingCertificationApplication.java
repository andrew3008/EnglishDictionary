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
        }

        System.out.println(PROCESS_OK_TOKEN);
    }
}
