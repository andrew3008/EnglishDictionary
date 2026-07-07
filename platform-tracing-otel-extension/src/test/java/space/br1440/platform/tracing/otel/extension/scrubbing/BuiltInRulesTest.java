package space.br1440.platform.tracing.otel.extension.scrubbing;


import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты встроенных правил scrubbing'а на API {@code evaluate(String key, Object value)}.
 */
class BuiltInRulesTest {

    @Nested
    class Registry {
        @Test
        void resolve_возвращает_корректные_типы() {
            assertThat(BuiltInSensitiveDataRules.resolve("email")).isInstanceOf(EmailRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("jwt")).isInstanceOf(JwtRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("password")).isInstanceOf(PasswordKeyRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("oauth-header")).isInstanceOf(OAuthHeaderRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("x-auth-header")).isInstanceOf(XAuthHeaderRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("infra-credential")).isInstanceOf(InfraCredentialRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("webhook-token")).isInstanceOf(WebhookTokenRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("ssh-credential")).isInstanceOf(SshCredentialRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("user-identity")).isInstanceOf(UserIdentityRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("hardware-identity")).isInstanceOf(HardwareIdentityRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("location")).isInstanceOf(LocationRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("ip-address")).isInstanceOf(IpAddressRule.class);
        }

        @Test
        void resolve_не_зависит_от_регистра_и_возвращает_null_для_неизвестных() {
            assertThat(BuiltInSensitiveDataRules.resolve("EMAIL")).isInstanceOf(EmailRule.class);
            assertThat(BuiltInSensitiveDataRules.resolve("unknown")).isNull();
            assertThat(BuiltInSensitiveDataRules.resolve(null)).isNull();
        }

        @Test
        void приоритеты_заданы_в_enum_и_возрастают_от_строгих_к_мягким_действиям() {
            // Единый источник истины — BuiltInSensitiveDataRules enum.
            assertThat(BuiltInSensitiveDataRules.OAUTH_HEADER.priority())
                    .isLessThan(BuiltInSensitiveDataRules.X_AUTH_HEADER.priority());
            assertThat(BuiltInSensitiveDataRules.X_AUTH_HEADER.priority())
                    .isLessThan(BuiltInSensitiveDataRules.JWT.priority());
            assertThat(BuiltInSensitiveDataRules.JWT.priority())
                    .isLessThan(BuiltInSensitiveDataRules.PASSWORD_KEY.priority());
            assertThat(BuiltInSensitiveDataRules.PASSWORD_KEY.priority())
                    .isLessThan(BuiltInSensitiveDataRules.HARDWARE_IDENTITY.priority());
            assertThat(BuiltInSensitiveDataRules.HARDWARE_IDENTITY.priority())
                    .isLessThan(BuiltInSensitiveDataRules.EMAIL.priority());
            assertThat(BuiltInSensitiveDataRules.EMAIL.priority())
                    .isLessThan(BuiltInSensitiveDataRules.IP_ADDRESS.priority());
            // Реализации делегируют priority из enum.
            assertThat(new OAuthHeaderRule().priority())
                    .isEqualTo(BuiltInSensitiveDataRules.OAUTH_HEADER.priority());
        }
    }

    @Test
    void password_key_правило_дропает_по_имени() {
        SensitiveDataRule rule = new PasswordKeyRule();
        assertThat(rule.evaluate("db.password", "x").action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("app.client-secret", "x").action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("user.name", "ivan").action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void jwt_правило_дропает_только_строковое_значение_с_eyJ() {
        SensitiveDataRule rule = new JwtRule();
        assertThat(rule.evaluate("v", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig").action())
                .isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("v", "просто текст").action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(rule.evaluate("v", 123L).action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void email_правило_хэширует_строку_с_email() {
        SensitiveDataRule rule = new EmailRule();
        assertThat(rule.evaluate("c", "ivan@example.com").action()).isEqualTo(ScrubbingAction.HASH);
        assertThat(rule.evaluate("c", "no email here").action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void user_identity_дропает_фио_и_хэширует_логин() {
        SensitiveDataRule rule = new UserIdentityRule();
        assertThat(rule.evaluate("user.firstName", "Иван").action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("user.family_name", "Петров").action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("preferred_username", "ipetrov").action()).isEqualTo(ScrubbingAction.HASH);
    }

    @Test
    void location_дропает_геокоординаты() {
        SensitiveDataRule rule = new LocationRule();
        assertThat(rule.evaluate("geo.latitude", 55.7).action())
                .isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("geo.speed", 10.0).action())
                .isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void ip_address_возвращает_truncate_с_маркером() {
        SensitiveDataRule rule = new IpAddressRule();
        var decision = rule.evaluate("client.address", "10.1.2.3");
        assertThat(decision.action()).isEqualTo(ScrubbingAction.TRUNCATE);
        assertThat(decision.reason()).isEqualTo(IpAddressRule.REASON);
    }

    @Test
    void critical_признак_у_секьюрити_правил_true_у_остальных_false() {
        assertThat(new OAuthHeaderRule().critical()).isTrue();
        assertThat(new PasswordKeyRule().critical()).isTrue();
        assertThat(new JwtRule().critical()).isTrue();
        assertThat(new EmailRule().critical()).isFalse();
        assertThat(new IpAddressRule().critical()).isFalse();
        assertThat(new LocationRule().critical()).isFalse();
    }
}
