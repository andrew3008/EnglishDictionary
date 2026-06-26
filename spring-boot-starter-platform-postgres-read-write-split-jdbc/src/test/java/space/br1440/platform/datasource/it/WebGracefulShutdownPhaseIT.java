package space.br1440.platform.datasource.it;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import space.br1440.platform.datasource.postgres.internal.proxy.lifecycle.DataSourceRoutingLifecycle;
import space.br1440.platform.datasource.support.junit.ClusterRoutingItProperties;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регресс-тест на фазу {@link DataSourceRoutingLifecycle#getPhase()}.
 * <p>Проверяет, что in-flight HTTP-запрос, удерживающий JDBC-соединение
 * через {@code pg_sleep(2)}, успевает корректно завершиться при graceful shutdown,
 * прежде чем HikariCP-пулы будут закрыты.
 *
 * <h2>Защищаемый инвариант</h2>
 * <p>{@code DataSourceRoutingLifecycle.getPhase() < WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE}.
 * Spring останавливает SmartLifecycle-бины в порядке убывания фазы, поэтому web-сервер
 * (фаза {@code DEFAULT_PHASE - 1024}) останавливается раньше пулов
 * (фаза {@code DEFAULT_PHASE - 2048}).
 *
 * <h2>Класс регрессий, который ловится</h2>
 * <ul>
 *   <li>Возврат к {@code Integer.MAX_VALUE - 1}: пулы закрылись бы первыми → in-flight
 *       SELECT упал бы с {@code SQLException: HikariDataSource has been closed}.</li>
 *   <li>Случайное использование значения {@code DEFAULT_PHASE - 512} (между web-сервером
 *       и пулами) — тест зафиксирует регрессию через {@code assertThat(getPhase()).isLessThan(...)}.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("WebGracefulShutdownPhaseIT: фазовый контракт с web-сервером")
class WebGracefulShutdownPhaseIT {

    private ConfigurableApplicationContext context;
    private int port;

    @BeforeAll
    void start(PgCluster cluster) {
        Map<String, Object> props = new HashMap<>(ClusterRoutingItProperties.baseClusterContextProperties(cluster));
        props.put("spring.main.web-application-type", "servlet");
        props.put("spring.main.banner-mode", "off");
        // Tomcat + graceful shutdown.
        props.put("server.port", "0");
        props.put("server.shutdown", "graceful");
        // Бюджет на graceful — должен покрыть pg_sleep(2) с запасом.
        props.put("spring.lifecycle.timeout-per-shutdown-phase", "10s");

        SpringApplication app = new SpringApplication(WebApp.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setDefaultProperties(props);
        this.context = app.run();
        this.port = context.getEnvironment().getProperty("local.server.port", Integer.class, 0);
        if (this.port == 0) {
            this.port = context.getBean(org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext.class)
                    .getWebServer().getPort();
        }
    }

    @AfterAll
    void teardown() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Фазовый контракт: phase пулов строго меньше фазы web-сервера")
    void phaseContract_poolsAfterWebServer() {
        DataSourceRoutingLifecycle lifecycle = context.getBean(DataSourceRoutingLifecycle.class);

        // Strong-assertion: пулы должны закрываться ПОСЛЕ web-сервера (фаза меньше).
        // Spring stop() порядок — по убыванию фазы.
        assertThat(lifecycle.getPhase())
                .as("пулы должны иметь фазу строго меньше web-сервера, иначе закроются раньше него")
                .isLessThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE)
                .isEqualTo(SmartLifecycle.DEFAULT_PHASE - 2048);
    }

    @Test
    @Order(2)
    @DisplayName("In-flight pg_sleep(2) запрос завершается успешно при graceful shutdown")
    void inflightRequest_completesBeforePoolsClose() throws Exception {
        HikariDataSource master = (HikariDataSource) context.getBean("writeDataSource");
        HikariDataSource replica = (HikariDataSource) context.getBean("readDataSource");

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/slow"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        // Шлём запрос асинхронно — на сервере запустится pg_sleep(2).
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        // Даём серверу принять запрос и зайти в pg_sleep.
        TimeUnit.MILLISECONDS.sleep(300);

        // Триггерим graceful shutdown: web-сервер дождётся in-flight запросов,
        // и только потом закроются пулы.
        long shutdownStart = System.nanoTime();
        context.close();
        long shutdownNanos = System.nanoTime() - shutdownStart;

        // Запрос должен был успеть отдать 200 OK.
        HttpResponse<String> response = future.get(15, TimeUnit.SECONDS);
        assertThat(response.statusCode())
                .as("in-flight запрос должен завершиться успешно при правильной фазе пулов")
                .isEqualTo(200);
        assertThat(response.body()).contains("ok");

        // Shutdown не мог быть мгновенным — мы ждали pg_sleep(2).
        assertThat(Duration.ofNanos(shutdownNanos).toMillis())
                .as("graceful shutdown должен был дождаться in-flight pg_sleep(2)")
                .isGreaterThanOrEqualTo(1500);

        // Пулы должны быть корректно закрыты.
        assertThat(master.isClosed()).as("write-пул должен быть закрыт после shutdown").isTrue();
        assertThat(replica.isClosed()).as("read-пул должен быть закрыт после shutdown").isTrue();
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class WebApp {

        @RestController
        public static class SlowController {
            private final JdbcTemplate jdbc;

            public SlowController(JdbcTemplate jdbc) {
                this.jdbc = jdbc;
            }

            @GetMapping("/slow")
            public String slow() {
                jdbc.execute("SELECT pg_sleep(2)");
                return "ok";
            }
        }
    }
}

