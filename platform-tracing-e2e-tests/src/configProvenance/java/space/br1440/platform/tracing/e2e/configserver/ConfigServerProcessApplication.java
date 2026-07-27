package space.br1440.platform.tracing.e2e.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableConfigServer
public class ConfigServerProcessApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerProcessApplication.class, args);
    }
}
