package space.br1440.platform.tracing.e2e.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Дочерний main для e2e smoke runtime-управления sampling'ом (PR-C, ADR-runtime-sampling-policy).
 * <p>
 * В отличие от {@link AgentSpringForceSamplingSmokeMain} живёт <b>несколько фаз</b>: тест шлёт
 * серию запросов, меняет ratio на лету через {@code POST /admin/sampling-ratio} (in-process
 * {@link MBeanServer} — та же стандартная точка обмена application-CL ↔ agent-CL, что и в
 * {@code PerfAdminController} перф-стенда), шлёт вторую серию и завершает процесс через
 * {@code POST /admin/shutdown}.
 * <p>
 * Контракт READY/args см. {@link AgentHttpSpringSmokeProcessRunner}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "space.br1440.platform.logging.configuration.LoggingAutoConfiguration",
        "space.br1440.platform.logging.configuration.GrpcLoggingConfiguration"
})
@Import(RuntimeSamplingControlSmokeMain.SmokeController.class)
public class RuntimeSamplingControlSmokeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: RuntimeSamplingControlSmokeMain <port> <flushDelayMs>");
        }
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);

        SpringApplication application = new SpringApplication(RuntimeSamplingControlSmokeMain.class);
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--platform.tracing.suppression.suppress-micrometer-tracing=true",
                "--logging.level.root=WARN");

        CountDownLatch shutdownLatch = context.getBean(CountDownLatch.class);

        System.out.println(AgentHttpSpringSmokeProcessRunner.READY_MARKER);
        System.out.flush();

        try {
            if (!shutdownLatch.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("POST /admin/shutdown не получен за 120 с");
            }
            // Даём BSP время экспортировать span'ы последней фазы до закрытия контекста.
            Thread.sleep(flushDelayMs);
        } finally {
            context.close();
        }
    }

    @Bean
    CountDownLatch shutdownLatch() {
        return new CountDownLatch(1);
    }

    /**
     * Эндпоинты теста: {@code /phase1}/{@code /phase2} — нагрузочные фазы (имена SERVER-span'ов
     * различимы в Jaeger), {@code /admin/*} — управление. Admin-пути не входят в drop-префиксы,
     * но их span'ы тесту не важны: assert'ы считают только {@code GET /phaseN}.
     */
    @RestController
    static class SmokeController {

        private static final String MBEAN_OBJECT_NAME =
                "space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl";

        private final CountDownLatch shutdownLatch;
        private final MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

        SmokeController(CountDownLatch shutdownLatch) {
            this.shutdownLatch = shutdownLatch;
        }

        @GetMapping("/phase1")
        String phase1() {
            return "ok";
        }

        @GetMapping("/phase2")
        String phase2() {
            return "ok";
        }

        /**
         * Runtime-смена head-sampling ratio через платформенный MBean. Возвращает новую версию
         * конфигурации — тест проверяет, что апдейт реально принят agent-side holder'ом
         * (инвариант C-6 ADR: каждый апдейт версионирован).
         */
        @PostMapping("/admin/sampling-ratio")
        ResponseEntity<String> setSamplingRatio(@RequestParam("value") double value) {
            try {
                ObjectName name = new ObjectName(MBEAN_OBJECT_NAME);
                mbeanServer.setAttribute(name, new Attribute("SamplingRatio", value));
                Object version = mbeanServer.getAttribute(name, "SamplingConfigVersion");
                return ResponseEntity.ok("ratio=" + value + ";version=" + version);
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(String.valueOf(e));
            }
        }

        @PostMapping("/admin/shutdown")
        String shutdown() {
            shutdownLatch.countDown();
            return "bye";
        }
    }
}
