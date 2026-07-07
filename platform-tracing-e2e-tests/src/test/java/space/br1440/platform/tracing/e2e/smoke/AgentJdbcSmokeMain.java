package space.br1440.platform.tracing.e2e.smoke;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Минимальный JDBC-клиент для smoke-теста OTel Java Agent.
 * <p>
 * Запускается в <b>отдельной JVM</b> с {@code -javaagent:opentelemetry-javaagent.jar},
 * потому что premain-инструментация невозможна внутри уже стартовавшего JUnit-процесса.
 * <p>
 * Аргументы: {@code jdbcUrl username password flushDelayMs}
 */
public final class AgentJdbcSmokeMain {

    private AgentJdbcSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Expected args: jdbcUrl username password [flushDelayMs]");
        }
        String jdbcUrl = args[0];
        String username = args[1];
        String password = args[2];
        long flushDelayMs = args.length >= 4 ? Long.parseLong(args[3]) : 3_000L;

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("SELECT 1 returned no rows");
            }
        }

        // Даём BatchSpanProcessor Agent'а время экспортировать span в OTLP/Jaeger.
        Thread.sleep(flushDelayMs);
    }
}
