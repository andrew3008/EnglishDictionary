package space.br1440.platform.tracing.e2e.configserver.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import space.br1440.platform.tracing.e2e.configserver.ConfigServerTestApplication;

public final class ConfigClientHarness implements AutoCloseable {

    private final ConfigurableApplicationContext context;

    private ConfigClientHarness(ConfigurableApplicationContext context) {
        this.context = context;
    }

    public static ConfigClientHarness start(String configServerUri, String... additionalArguments) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--spring.application.name=provenance-client");
        arguments.add("--spring.config.import=configserver:");
        arguments.add("--spring.cloud.config.enabled=true");
        arguments.add("--spring.cloud.config.uri=" + configServerUri);
        arguments.add("--spring.cloud.config.fail-fast=true");
        arguments.add("--spring.cloud.config.label=main");
        arguments.add("--spring.main.web-application-type=none");
        arguments.add("--spring.jmx.enabled=false");
        arguments.add("--management.endpoints.enabled-by-default=false");
        arguments.add("--logging.level.root=ERROR");
        arguments.addAll(List.of(additionalArguments));

        SpringApplication application = new SpringApplication(ConfigServerTestApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return new ConfigClientHarness(application.run(arguments.toArray(String[]::new)));
    }

    public static String additionalLocation(Path directory) {
        String uri = directory.toUri().toString();
        return "--spring.config.additional-location=" + (uri.endsWith("/") ? uri : uri + "/");
    }

    public ConfigurableEnvironment environment() {
        return context.getEnvironment();
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    @Override
    public void close() {
        context.close();
    }
}
