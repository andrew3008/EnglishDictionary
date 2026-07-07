package space.br1440.platform.tracing.bench;

/**
 * Общие fixtures scrubbing-бенчмарков: пары «ключ/значение», гарантированно матчащиеся
 * соответствующим built-in правилом (hit-путь).
 * <p>
 * Контракт «fixture даёт hit, а не KEEP» охраняется
 * {@code ScrubbingBenchmarkFixtureContractTest} (src/test держит синхронную копию таблицы,
 * т.к. src/jmh не виден из test-classpath).
 */
final class ScrubbingFixtures {

    private ScrubbingFixtures() {
    }

    static HitFixture hitFixtureFor(String name) {
        return switch (name) {
            case "oauth-header" -> new HitFixture("http.request.header.authorization",
                    "Bearer 0a1b2c3d4e5f60718293a4b5c6d7e8f9");
            case "x-auth-header" -> new HitFixture("http.request.header.x-auth-header",
                    "{\"email\":\"ivan@example.com\",\"permissions\":[\"orders:read\"]}");
            case "jwt" -> new HitFixture("token",
                    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                            + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0"
                            + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c");
            case "webhook-token" -> new HitFixture("webhook_token",
                    "whk-0a1b2c3d4e5f60718293a4b5c6d7e8f9");
            case "password" -> new HitFixture("db.password", "s3cr3t-value");
            case "infra-credential" -> new HitFixture("postgres_password",
                    "pg-s3cr3t-value");
            case "ssh-credential" -> new HitFixture("ssh.private_key",
                    "-----BEGIN OPENSSH PRIVATE KEY-----");
            case "location" -> new HitFixture("geo.latitude", "55.751244");
            case "hardware-identity" -> new HitFixture("device.imei",
                    "490154203237518");
            case "user-identity" -> new HitFixture("preferred_username", "ivan.petrov");
            case "email" -> new HitFixture("user.email", "ivan.petrov@example.com");
            case "ip-address" -> new HitFixture("client.address", "192.168.100.70");
            default -> throw new IllegalStateException("Нет fixture для правила: " + name);
        };
    }

    record HitFixture(String key, Object value) { }
}
