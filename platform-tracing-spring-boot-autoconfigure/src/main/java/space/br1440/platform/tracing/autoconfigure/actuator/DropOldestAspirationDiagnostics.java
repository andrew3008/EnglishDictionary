package space.br1440.platform.tracing.autoconfigure.actuator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Startup-диагностика политики переполнения очереди экспорта.
 * <p>
 * Начиная с v1.x платформенный default — {@code DROP_OLDEST} (§2.5 Traces Requests.txt):
 * Agent extension автоматически подставляет его через {@code PlatformTracingDefaultsProvider}.
 * Диагностика выявляет случаи явного отключения (оператор задал {@code UPSTREAM}), чтобы
 * Spring-конфигурация (Spring {@code queue.policy=DROP_OLDEST}) не расходилась с Agent runtime.
 *
 * <table>
 *   <caption>Матрица сигналов</caption>
 *   <tr><th>Spring {@code queue.policy}</th><th>Agent {@code overflow-policy}</th><th>Сигнал</th></tr>
 *   <tr><td>{@code DROP_OLDEST}</td><td>{@code DROP_OLDEST} (default или явный)</td><td>optional INFO (aligned)</td></tr>
 *   <tr><td>{@code DROP_OLDEST}</td><td>{@code UPSTREAM} (явно задан оператором)</td><td><b>WARN</b> — оператор явно отключил DROP_OLDEST; Agent использует stock BSP (drop-new)</td></tr>
 *   <tr><td>{@code DROP_NEWEST}</td><td>любой</td><td>вне scope (не логируется)</td></tr>
 * </table>
 *
 * <h2>Управление</h2>
 * Включается флагами {@link TracingProperties.Diagnostics#isDropOldestAspirationWarn()} и
 * {@link TracingProperties.Diagnostics#isDropOldestAspirationInfo()} (оба {@code true} по
 * умолчанию). См. {@code docs/decisions/ADR-drop-oldest-export-processor-v1.md}.
 */
public final class DropOldestAspirationDiagnostics implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DropOldestAspirationDiagnostics.class);

    /** Spring property name. Дублируется здесь сознательно: модуль не зависит от extension. */
    public static final String SPRING_POLICY_PROPERTY = "platform.tracing.queue.policy";

    /** Agent property name ({@code platform.tracing.queue.overflow-policy} в OTel ConfigProperties). */
    public static final String AGENT_OVERFLOW_POLICY_PROPERTY = "platform.tracing.queue.overflow-policy";

    /** Имя env-var для Agent overflow-policy ({@code PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY}). */
    public static final String AGENT_OVERFLOW_POLICY_ENV = "PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY";

    /** Имя System property для Agent overflow-policy. */
    public static final String AGENT_OVERFLOW_POLICY_SYSPROP = AGENT_OVERFLOW_POLICY_PROPERTY;

    /** Имя source'а Spring Boot, добавляющего платформенные default-значения. */
    private static final String DEFAULT_PROPERTIES_SOURCE_NAME = "defaultProperties";

    /** Маркер невозможности misconfiguration — повторяет тон {@link DualChannelDriftDiagnostics}. */
    private static final String NOT_A_MISCONFIG_MARKER =
            "WARN does not mean application misconfiguration. "
                    + "It means Spring 'desired' policy and Agent 'effective' policy diverge. "
                    + "Spring side is not auto-bridged to Agent; см. ADR-drop-oldest-export-processor-v1.";

    private final TracingProperties properties;
    private final ConfigurableEnvironment environment;
    private final Function<String, String> systemPropertyLookup;
    private final Function<String, String> envLookup;
    private final boolean warnEnabled;
    private final boolean infoEnabled;

    /** Production-конструктор: использует {@link System#getProperty} и {@link System#getenv}. */
    public DropOldestAspirationDiagnostics(TracingProperties properties,
                                           ConfigurableEnvironment environment,
                                           boolean warnEnabled,
                                           boolean infoEnabled) {
        this(properties, environment, System::getProperty, System::getenv, warnEnabled, infoEnabled);
    }

    /** Полный конструктор для unit-тестов с подменой источников System/Env. */
    DropOldestAspirationDiagnostics(TracingProperties properties,
                                    ConfigurableEnvironment environment,
                                    Function<String, String> systemPropertyLookup,
                                    Function<String, String> envLookup,
                                    boolean warnEnabled,
                                    boolean infoEnabled) {
        this.properties = properties;
        this.environment = environment;
        this.systemPropertyLookup = systemPropertyLookup;
        this.envLookup = envLookup;
        this.warnEnabled = warnEnabled;
        this.infoEnabled = infoEnabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!warnEnabled && !infoEnabled) {
            return;
        }
        TracingProperties.Queue.OverflowPolicy springPolicy = properties.getQueue().getPolicy();
        if (springPolicy != TracingProperties.Queue.OverflowPolicy.DROP_OLDEST) {
            // Out of scope: DROP_NEWEST не покрывается v1.x — нет custom processor.
            return;
        }
        String agentPolicy = resolveAgentOverflowPolicy();
        // Платформенный default Agent'а — DROP_OLDEST (через PlatformTracingDefaultsProvider).
        // UPSTREAM появляется только если оператор явно задал это значение.
        boolean agentUpstream = "UPSTREAM".equals(agentPolicy);

        if (!agentUpstream) {
            // DROP_OLDEST активен (default или явный): Spring и Agent согласованы.
            if (infoEnabled) {
                log.info("Tracing overflow policy aligned: Spring queue.policy=DROP_OLDEST, "
                                + "Agent {}={} — гарантированный DROP_OLDEST активирован (§2.5).",
                        AGENT_OVERFLOW_POLICY_PROPERTY,
                        agentPolicy == null ? "DROP_OLDEST (platform default)" : agentPolicy);
            }
            return;
        }
        // Оператор явно выбрал UPSTREAM — Agent использует stock BSP (drop-new по факту).
        // Spring-конфигурация (DROP_OLDEST) расходится с Agent runtime.
        if (warnEnabled) {
            log.warn("Tracing overflow policy mismatch: Spring {}={}, но Agent {}=UPSTREAM "
                            + "(оператор явно задал stock BatchSpanProcessor → drop-new по факту). "
                            + "Для активации гарантированного DROP_OLDEST удалите {}=UPSTREAM "
                            + "или задайте {}=DROP_OLDEST. "
                            + NOT_A_MISCONFIG_MARKER,
                    SPRING_POLICY_PROPERTY, springPolicy,
                    AGENT_OVERFLOW_POLICY_PROPERTY,
                    AGENT_OVERFLOW_POLICY_ENV,
                    AGENT_OVERFLOW_POLICY_ENV);
        }
    }

    /**
     * Возвращает строковое значение Agent overflow-policy в нормализованном виде ({@code DROP_OLDEST}
     * / {@code UPSTREAM} / любое исходное значение в верхнем регистре), либо {@code null}, если
     * свойство не задано ни через System property, ни через env-var.
     * <p>
     * <b>Внимание:</b> в classloader application (где живёт этот бин) у нас нет доступа к
     * OTel {@code ConfigProperties} — реальные значения принимает agent extension в своём
     * isolated classloader'е. Поэтому мы читаем то же, что увидит оператор через JMX/OS:
     * системные свойства и переменные окружения. PropertiesSupplier из extension (default
     * UPSTREAM или env-fallback) сюда не доходит — это сознательное ограничение диагностики.
     */
    private String resolveAgentOverflowPolicy() {
        String sys = systemPropertyLookup.apply(AGENT_OVERFLOW_POLICY_SYSPROP);
        if (sys != null && !sys.isBlank()) {
            return sys.trim().toUpperCase(Locale.ROOT);
        }
        String env = envLookup.apply(AGENT_OVERFLOW_POLICY_ENV);
        if (env != null && !env.isBlank()) {
            return env.trim().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * Возвращает {@code true}, если {@link #SPRING_POLICY_PROPERTY} присутствует хотя бы в одном
     * {@link EnumerablePropertySource} помимо source'ов, имя которых начинается с
     * {@value #DEFAULT_PROPERTIES_SOURCE_NAME} (это и есть платформенный default,
     * добавленный, например, через {@code SpringApplicationBuilder.properties()}).
     */
    private boolean isSpringPolicyExplicit() {
        for (PropertySource<?> source : environment.getPropertySources()) {
            String name = source.getName();
            if (name != null && name.startsWith(DEFAULT_PROPERTIES_SOURCE_NAME)) {
                continue;
            }
            if (source instanceof EnumerablePropertySource<?> enumerable
                    && containsProperty(enumerable, SPRING_POLICY_PROPERTY)) {
                return true;
            }
            if (source instanceof MapPropertySource map
                    && map.containsProperty(SPRING_POLICY_PROPERTY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spring-friendly проверка наличия ключа: учитываем relaxed-binding варианты
     * ({@code platform.tracing.queue.policy} = {@code PLATFORM_TRACING_QUEUE_POLICY}, и т.п.).
     */
    private static boolean containsProperty(EnumerablePropertySource<?> source, String key) {
        if (source.containsProperty(key)) {
            return true;
        }
        String upperUnderscored = key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        for (String existing : source.getPropertyNames()) {
            if (existing == null) {
                continue;
            }
            String normalized = existing.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
            if (normalized.equals(upperUnderscored)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Снимок текущего состояния для встраивания в {@code /actuator/tracing} (как
     * map-фрагмент). Содержит исходные и эффективные значения, без чувствительных данных.
     */
    public Map<String, Object> snapshot() {
        TracingProperties.Queue.OverflowPolicy springPolicy = properties.getQueue().getPolicy();
        String agentPolicy = resolveAgentOverflowPolicy();
        return Map.of(
                "springPolicy", String.valueOf(springPolicy),
                "springPolicyExplicit", isSpringPolicyExplicit(),
                // null = не задан явно; платформенный default Agent'а = DROP_OLDEST.
                "agentPolicy", agentPolicy == null ? "DROP_OLDEST (platform default)" : agentPolicy,
                // активен если не UPSTREAM: null → DROP_OLDEST default, "DROP_OLDEST" → явный.
                "agentDropOldestActive", !"UPSTREAM".equals(agentPolicy),
                "warnEnabled", warnEnabled,
                "infoEnabled", infoEnabled
        );
    }

    /** Фабричный метод для unit-тестов: подмена системных источников. */
    static DropOldestAspirationDiagnostics forTest(TracingProperties properties,
                                                   ConfigurableEnvironment environment,
                                                   Function<String, String> sysProps,
                                                   Function<String, String> envVars,
                                                   boolean warnEnabled,
                                                   boolean infoEnabled) {
        return new DropOldestAspirationDiagnostics(
                properties, environment, sysProps, envVars, warnEnabled, infoEnabled);
    }
}
