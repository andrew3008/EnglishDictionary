package space.br1440.platform.tracing.bench.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.extension.scrubbing.ScrubbingSnapshot;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.RuleExecutionWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт fixtures бенчмарка {@code ScrubbingEngineBenchmark} (Фаза 17, PR-1).
 * <p>
 * Защита от «тихого» измерения не той ветки: если fixture per-rule сценария перестаёт
 * матчиться своим правилом (например, после изменения regex'а правила), бенчмарк начнёт
 * измерять miss-путь вместо hit-пути — и данные ADR-scrubbing-cost станут неверными
 * без какого-либо сигнала. Этот тест фейлится при таком дрейфе.
 * <p>
 * Дублирует таблицу {@code hitFixtureFor} бенчмарка намеренно: бенчмарк живёт в
 * {@code src/jmh} и не виден из {@code src/test}; расхождение таблиц проявится здесь же.
 */
class ScrubbingBenchmarkFixtureContractTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "oauth-header", "x-auth-header", "jwt", "webhook-token", "password",
            "infra-credential", "ssh-credential", "location", "hardware-identity",
            "user-identity", "email", "ip-address"
    })
    void fixture_каждого_правила_даёт_hit_а_не_keep(String ruleName) {
        SpanAttributeScrubbingRule rule = BuiltInSpanAttributeScrubbingRules.resolve(ruleName);
        assertThat(rule).as("правило %s должно резолвиться", ruleName).isNotNull();

        HitFixture fixture = hitFixtureFor(ruleName);
        List<RuleExecutionWrapper> wrappers = ScrubbingSnapshot.compileWrappers(List.of(rule));
        ScrubbingDecision decision = MergeEngine.evaluate(
                wrappers, fixture.key().toLowerCase(Locale.ROOT).trim(), fixture.value());

        assertThat(decision.action())
                .as("fixture правила %s (key=%s) обязан давать hit (не KEEP), иначе "
                                + "ScrubbingEngineBenchmark.perRuleHit измеряет miss-путь",
                        ruleName, fixture.key())
                .isNotEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void clean_fixture_не_матчится_ни_одним_default_правилом() {
        List<SpanAttributeScrubbingRule> defaults = new ArrayList<>();
        for (String name : BuiltInSpanAttributeScrubbingRules.defaultConfigNames()) {
            SpanAttributeScrubbingRule r = BuiltInSpanAttributeScrubbingRules.resolve(name);
            if (r != null) {
                defaults.add(r);
            }
        }
        List<RuleExecutionWrapper> wrappers = ScrubbingSnapshot.compileWrappers(defaults);

        ScrubbingDecision shortClean = MergeEngine.evaluate(
                wrappers, "http.route", "/api/v1/orders/{id}");
        assertThat(shortClean.action())
                .as("clean-key fixture бенча обязан оставаться KEEP (hot-path без hit'а)")
                .isEqualTo(ScrubbingAction.KEEP);

        ScrubbingDecision longClean = MergeEngine.evaluate(
                wrappers, "http.route", "orders payload segment ".repeat(90));
        assertThat(longClean.action())
                .as("длинное clean-значение бенча обязано оставаться KEEP")
                .isEqualTo(ScrubbingAction.KEEP);
    }

    /**
     * Копия таблицы fixtures из {@code ScrubbingEngineBenchmark.hitFixtureFor}.
     * При изменении бенчмарка обновлять синхронно.
     */
    private static HitFixture hitFixtureFor(String name) {
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

    private record HitFixture(String key, Object value) { }
}
