package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Реестр встроенных правил scrubbing'а, релевантных домену UMS.
 * <p>
 * Каждая константа enum хранит <b>имя конфигурации</b> ({@code platform.tracing.scrubbing.built-in-rules})
 * и <b>приоритет first-match</b> (меньше = выше). Логика срабатывания — в отдельных классах
 * {@link PasswordKeyRule}, {@link JwtRule} и т.д., наследующих {@link AbstractBuiltInRule}.
 * <p>
 * SPI-расширения через {@link java.util.ServiceLoader} в этот enum не входят.
 */
public enum BuiltInSpanAttributeScrubbingRules {

    OAUTH_HEADER("oauth-header", 10, true),
    X_AUTH_HEADER("x-auth-header", 15, true),
    JWT("jwt", 20, true),
    WEBHOOK_TOKEN("webhook-token", 25, true),
    PASSWORD_KEY("password", 50, true),
    INFRA_CREDENTIAL("infra-credential", 55, true),
    SSH_CREDENTIAL("ssh-credential", 60, true),
    LOCATION("location", 100, false),
    HARDWARE_IDENTITY("hardware-identity", 110, false),
    USER_IDENTITY("user-identity", 120, false),
    EMAIL("email", 130, false),
    IP_ADDRESS("ip-address", 140, false);

    private final String configName;
    private final int priority;
    private final boolean critical;

    BuiltInSpanAttributeScrubbingRules(String configName, int priority, boolean critical) {
        this.configName = configName;
        this.priority = priority;
        this.critical = critical;
    }

    /** Имя правила в конфигурации ({@code built-in-rules}). */
    public String configName() {
        return configName;
    }

    /** Приоритет first-match: меньшее значение — выше приоритет. */
    public int priority() {
        return priority;
    }

    /**
     * Является ли правило critical built-in (защита секретов: заголовки авторизации, пароли,
     * токены, инфраструктурные/ssh-креды). Только такие правила формируют терминальные решения
     * и переходят в fail-closed при сбое (см. PR-3/PR-4/PR-5).
     */
    public boolean critical() {
        return critical;
    }

    /**
     * Создаёт новый экземпляр реализации правила. Резолвер вызывает на каждый resolve —
     * экземпляры stateless, повторное создание безопасно.
     */
    public SpanAttributeScrubbingRule create() {
        return switch (this) {
            case OAUTH_HEADER -> new OAuthHeaderRule();
            case X_AUTH_HEADER -> new XAuthHeaderRule();
            case JWT -> new JwtRule();
            case WEBHOOK_TOKEN -> new WebhookTokenRule();
            case PASSWORD_KEY -> new PasswordKeyRule();
            case INFRA_CREDENTIAL -> new InfraCredentialRule();
            case SSH_CREDENTIAL -> new SshCredentialRule();
            case LOCATION -> new LocationRule();
            case HARDWARE_IDENTITY -> new HardwareIdentityRule();
            case USER_IDENTITY -> new UserIdentityRule();
            case EMAIL -> new EmailRule();
            case IP_ADDRESS -> new IpAddressRule();
        };
    }

    /**
     * Возвращает дескриптор встроенного правила по имени конфигурации или {@code null},
     * если имя не распознано. Сравнение без учёта регистра. Не создаёт экземпляр правила.
     */
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

    /**
     * Возвращает реализацию встроенного правила по имени конфигурации или {@code null},
     * если имя не распознано. Сравнение без учёта регистра.
     */
    public static SpanAttributeScrubbingRule resolve(String name) {
        BuiltInSpanAttributeScrubbingRules descriptor = lookup(name);
        return descriptor == null ? null : descriptor.create();
    }

    /**
     * Дефолтный список имён встроенных правил для {@code platform.tracing.scrubbing.built-in-rules}.
     * Порядок — логический (для документации/YAML), не порядок исполнения: процессор сортирует
     * по {@link #priority()}.
     */
    public static List<String> defaultConfigNames() {
        return List.of(
                PASSWORD_KEY.configName(),
                JWT.configName(),
                EMAIL.configName(),
                OAUTH_HEADER.configName(),
                X_AUTH_HEADER.configName(),
                INFRA_CREDENTIAL.configName(),
                WEBHOOK_TOKEN.configName(),
                SSH_CREDENTIAL.configName(),
                USER_IDENTITY.configName(),
                HARDWARE_IDENTITY.configName(),
                LOCATION.configName(),
                IP_ADDRESS.configName()
        );
    }

    /**
     * Все зарегистрированные имена конфигурации (для контрактных тестов).
     */
    public static List<String> allConfigNames() {
        return Arrays.stream(values())
                .map(BuiltInSpanAttributeScrubbingRules::configName)
                .toList();
    }
}
