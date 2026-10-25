package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import java.util.List;
import java.util.Locale;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public enum BuiltInSpanAttributeScrubbingRules {

    OAUTH_HEADER("oauth-header", 10, true),
    X_AUTH_HEADER("x-auth-header", 15, true),
    JWT("jwt", 20, true),
    WEBHOOK_TOKEN("webhook-token", 25, true),
    PASSWORD_KEY("password", 50, true),
    SSH_CREDENTIAL("ssh-credential", 60, true),
    LOCATION("location", 100, false),
    HARDWARE_IDENTITY("hardware-identity", 110, false),
    USER_IDENTITY("user-identity", 120, false),
    EMAIL("email", 130, false),
    IP_ADDRESS("ip-address", 140, false);

    private final String configName;
    private final int priority;
    private final boolean critical;

    public String configName() {
        return configName;
    }

    public int priority() {
        return priority;
    }

    public boolean critical() {
        return critical;
    }

    public SpanAttributeScrubbingRule create() {
        return switch (this) {
            case OAUTH_HEADER -> new OAuthHeaderRule();
            case X_AUTH_HEADER -> new XAuthHeaderRule();
            case JWT -> new JwtRule();
            case WEBHOOK_TOKEN -> new WebhookTokenRule();
            case PASSWORD_KEY -> new PasswordKeyRule();
            case SSH_CREDENTIAL -> new SshCredentialRule();
            case LOCATION -> new LocationRule();
            case HARDWARE_IDENTITY -> new HardwareIdentityRule();
            case USER_IDENTITY -> new UserIdentityRule();
            case EMAIL -> new EmailRule();
            case IP_ADDRESS -> new IpAddressRule();
        };
    }

    public static BuiltInSpanAttributeScrubbingRules lookup(String name) {
        if (name == null) {
            return null;
        }

        String normalized = name.toLowerCase(Locale.ROOT);
        for (BuiltInSpanAttributeScrubbingRules rule : values()) {
            if (rule.configName.equals(normalized)) {
                return rule;
            }
        }

        return null;
    }

    public static SpanAttributeScrubbingRule resolve(String name) {
        BuiltInSpanAttributeScrubbingRules descriptor = lookup(name);
        return (descriptor == null) ? null : descriptor.create();
    }

    public static List<String> defaultConfigNames() {
        return List.of(
                PASSWORD_KEY.configName(),
                JWT.configName(),
                EMAIL.configName(),
                OAUTH_HEADER.configName(),
                X_AUTH_HEADER.configName(),
                WEBHOOK_TOKEN.configName(),
                SSH_CREDENTIAL.configName(),
                USER_IDENTITY.configName(),
                HARDWARE_IDENTITY.configName(),
                LOCATION.configName(),
                IP_ADDRESS.configName()
        );
    }
}
