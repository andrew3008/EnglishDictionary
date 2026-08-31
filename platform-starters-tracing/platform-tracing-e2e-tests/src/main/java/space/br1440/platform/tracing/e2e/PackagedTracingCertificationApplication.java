package space.br1440.platform.tracing.e2e;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class PackagedTracingCertificationApplication {

    static final String PROCESS_OK_TOKEN = "PACKAGED_PROCESS_OK";

    public static void main(String[] arguments) {
        SpringApplication application = new SpringApplication(PackagedTracingCertificationApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);

        try (ConfigurableApplicationContext ignored = application.run(arguments)) {
            // контекст нужен только ради жизненного цикла запуска и закрытия
        }

        System.out.println(PROCESS_OK_TOKEN);
    }
}
