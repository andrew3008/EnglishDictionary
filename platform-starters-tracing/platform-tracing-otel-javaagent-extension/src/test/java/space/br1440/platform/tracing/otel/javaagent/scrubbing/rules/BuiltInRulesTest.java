package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

class BuiltInRulesTest {

    @Nested
    class Registry {
        @Test
        void resolve_returns_correct_types() {
            // when/then
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("email")).isInstanceOf(EmailRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("jwt")).isInstanceOf(JwtRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("password")).isInstanceOf(PasswordKeyRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("oauth-header")).isInstanceOf(OAuthHeaderRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("x-auth-header")).isInstanceOf(XAuthHeaderRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("webhook-token")).isInstanceOf(WebhookTokenRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("ssh-credential")).isInstanceOf(SshCredentialRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("user-identity")).isInstanceOf(UserIdentityRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("hardware-identity")).isInstanceOf(HardwareIdentityRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("location")).isInstanceOf(LocationRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("ip-address")).isInstanceOf(IpAddressRule.class);
        }

        @Test
        void resolve_is_case_insensitive_and_returns_null_for_unknown() {
            // when/then
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("EMAIL")).isInstanceOf(EmailRule.class);
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve("unknown")).isNull();
            assertThat(BuiltInSpanAttributeScrubbingRules.resolve(null)).isNull();
        }

        @Test
        void priorities_are_defined_in_enum_and_increase_from_strict_to_soft_actions() {
            // Единый источник истины — BuiltInSpanAttributeScrubbingRules enum.
            // when/then
            assertThat(BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.priority())
                    .isLessThan(BuiltInSpanAttributeScrubbingRules.X_AUTH_HEADER.priority());
            assertThat(BuiltInSpanAttributeScrubbingRules.X_AUTH_HEADER.priority())
                    .isLessThan(BuiltInSpanAttributeScrubbingRules.JWT.priority());
            assertThat(BuiltInSpanAttributeScrubbingRules.JWT.priority())
                    .isLessThan(BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.priority());
            assertThat(BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.priority())
                    .isLessThan(BuiltInSpanAttributeScrubbingRules.HARDWARE_IDENTITY.priority());
            assertThat(BuiltInSpanAttributeScrubbingRules.HARDWARE_IDENTITY.priority())
                    .isLessThan(BuiltInSpanAttributeScrubbingRules.EMAIL.priority());
            assertThat(BuiltInSpanAttributeScrubbingRules.EMAIL.priority())
                    .isLessThan(BuiltInSpanAttributeScrubbingRules.IP_ADDRESS.priority());
            // Реализации делегируют priority из enum.
            assertThat(new OAuthHeaderRule().priority())
                    .isEqualTo(BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.priority());
        }
    }

    @Test
    void password_key_rule_drops_by_name() {
        // given
        SpanAttributeScrubbingRule rule = new PasswordKeyRule();
        // when/then
        assertThat(rule.evaluate("db.password", "x").action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("app.client-secret", "x").action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("user.name", "ivan").action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void user_identity_drops_full_name_and_hashes_login() {
        // given
        SpanAttributeScrubbingRule rule = new UserIdentityRule();
        // when/then
        assertThat(rule.evaluate("user.firstName", "Иван").action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("user.family_name", "Петров").action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("preferred_username", "ipetrov").action()).isEqualTo(ScrubbingAction.HASH);
    }

    @Test
    void location_drops_geocoordinates() {
        // given
        SpanAttributeScrubbingRule rule = new LocationRule();
        // when/then
        assertThat(rule.evaluate("geo.latitude", 55.7).action())
                .isEqualTo(ScrubbingAction.DROP);
        assertThat(rule.evaluate("geo.speed", 10.0).action())
                .isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void ip_address_returns_truncate_with_marker() {
        // given
        SpanAttributeScrubbingRule rule = new IpAddressRule();
        // when
        ScrubbingDecision decision = rule.evaluate("client.address", "10.1.2.3");
        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.TRUNCATE);
        assertThat(decision.reason()).isEqualTo(IpAddressRule.REASON);
    }

    @Test
    void critical_flag_true_for_security_rules_false_for_others() {
        // when/then
        assertThat(new OAuthHeaderRule().critical()).isTrue();
        assertThat(new PasswordKeyRule().critical()).isTrue();
        assertThat(new JwtRule().critical()).isTrue();
        assertThat(new EmailRule().critical()).isFalse();
        assertThat(new IpAddressRule().critical()).isFalse();
        assertThat(new LocationRule().critical()).isFalse();
    }
}
