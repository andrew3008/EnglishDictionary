package c3.consumer;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class ConsumerApplication {

    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(ConsumerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=ERROR",
                        "platform.tracing.enabled=false")
                .run(args)) {
            if (!context.isActive()) {
                throw new IllegalStateException("Spring context is not active");
            }
        }
    }
}
