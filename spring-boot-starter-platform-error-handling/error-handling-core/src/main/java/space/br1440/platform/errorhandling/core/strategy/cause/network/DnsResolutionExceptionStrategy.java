package space.br1440.platform.errorhandling.core.strategy.cause.network;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.CauseChainNavigator;
import space.br1440.platform.errorhandling.core.util.CauseMetadataEnricher;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production-grade стратегия обработки DNS-ошибок как первопричины бизнес-исключений.
 *
 * <p>Без выделенной стратегии DNS-ошибки обрабатываются в
 * {@link HttpIntegrationExceptionStrategy} как generic {@code NETWORK_ERROR}
 * или в {@link space.br1440.platform.errorhandling.core.strategy.cause.enterprise.FileIoExceptionStrategy}
 * как generic {@code IOException}, теряя диагностическую ценность
 * (hostname, тип сбоя DNS, Netty resolver context, Kubernetes service discovery details).
 *
 * <h2>Обрабатываемые исключения</h2>
 * <pre>
 * java.net.UnknownHostException                          → 503  UNKNOWN_HOST           retryable
 *   JDK InetAddress resolver: DNS-сервер не смог разрешить hostname в IP-адрес.
 *   RestTemplate, WebClient, Feign, gRPC, Kafka, RabbitMQ.
 *
 * java.nio.channels.UnresolvedAddressException           → 503  UNRESOLVED_ADDRESS     retryable
 *   NIO-каналы: SocketAddress не была предварительно разрешена перед connect().
 *   Netty, gRPC, async NIO I/O.
 *
 * io.netty.resolver.dns.DnsNameResolverTimeoutException  → 504  NETTY_DNS_TIMEOUT      retryable
 *   Netty async DNS resolver: DNS-сервер не ответил в пределах queryTimeout.
 *   WebClient / Reactor Netty с async DNS resolution.
 *
 * io.netty.resolver.dns.DnsNameResolverException         → 503  NETTY_DNS_FAILED       retryable
 *   Netty async DNS resolver: NXDOMAIN, SERVFAIL, сетевая недоступность
 *   DNS-сервера, malformed response.
 * </pre>
 *
 * <h2>Kubernetes / Service Mesh контекст</h2>
 * <p>В Kubernetes-среде DNS-ошибки — один из самых частых root cause:
 * <ul>
 *   <li>CoreDNS перезапуск / OOMKilled → transient DNS failures (категория {@code SERVFAIL})</li>
 *   <li>Service не создан / удалён → persistent NXDOMAIN</li>
 *   <li>{@code ndots:5} (K8s default) → search domain exhaustion,
 *       повышенная латентность DNS-запросов (до 5 дополнительных DNS queries
 *       для hostname с &lt; 5 точками); диагностируется через {@code hostnameDotsCount}</li>
 *   <li>DNS TTL cache invalidation → stale entries после rolling update</li>
 *   <li>Istio/Envoy sidecar → DNS interception failures</li>
 *   <li>Network Policy блокирует UDP:53 к CoreDNS</li>
 *   <li>JVM negative DNS cache ({@code networkaddress.cache.negative.ttl}) →
 *       кэширует NXDOMAIN результаты; при {@code -1} (бесконечно) — persistent failure
 *       даже после появления сервиса; диагностируется через {@code jdkDnsNegativeCacheTtlSec}</li>
 *   <li>JVM positive DNS cache ({@code networkaddress.cache.ttl}) → кэширует успешные
 *       DNS-ответы; при {@code -1} (Java &lt; 8 default) после миграции pod на новый IP
 *       клиент будет ходить в старый адрес до перезапуска JVM; диагностируется
 *       через {@code jdkDnsPositiveCacheTtlSec}</li>
 * </ul>
 *
 * <h2>Безопасность (CWE-209)</h2>
 * <p>Hostname намеренно <b>включается</b> в metadata — это основной
 * диагностический сигнал для DNS-ошибок. В Kubernetes-среде hostname
 * является публичным service name (e.g., {@code payment-service}),
 * не содержащим sensitive data.</p>
 * <p>Извлечённый из exception message hostname проходит валидацию по
 * RFC 1035 ({@link #VALID_HOSTNAME_PATTERN}) перед записью в metadata —
 * это защищает от попадания зашумлённых сообщений (например, OS-зависимых
 * фрагментов вида {@code nslookup}, IP-литералов, путей с пробелами) в
 * структурированные индексы Loki/Elasticsearch.</p>
 * <p>Намеренно <b>не включаются</b>:</p>
 * <ul>
 *   <li>Exception message целиком — может содержать resolver IP, search domain list,
 *       DNS server configuration</li>
 *   <li>DNS resolver address — IP DNS-сервера раскрывает внутреннюю инфраструктуру</li>
 *   <li>DNS search domain list — раскрывает Kubernetes namespace/cluster конфигурацию</li>
 *   <li>DNS TTL — недоступен из исключений JDK/Netty</li>
 * </ul>
 * <p>Kubernetes FQDN ({@code service.namespace.svc.cluster.local}) раскрывает
 * namespace и cluster domain — документировано как допустимый trade-off для
 * операционной диагностики (namespace — не секрет, он виден в любом kubectl output).</p>
 */
public class DnsResolutionExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    /**
     * Глубина обхода cause chain. Используется платформенная константа
     * {@link CauseMetadataEnricher#DIAGNOSTIC_CAUSE_CHAIN_DEPTH} (= 5),
     * как и в остальных network/messaging-стратегиях.
     *
     * <p>Реальные DNS-цепочки в production обычно укладываются в 3–4 уровня
     * (например: {@code WebClientRequestException → ConnectException →
     * UnknownHostException → DnsErrorCauseException}). Значение 5 даёт
     * достаточный запас и одновременно отсекает экзотические длинные
     * обёртки (Reactor SubscriberContext, Resilience4j retry decorators).
     */
    private static final int CAUSE_CHAIN_DEPTH = CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH;

    /** {@code service.namespace} — стандартный 2-сегментный K8s prefix перед {@code .svc.}. */
    private static final int STD_SVC_PARTS = 2;

    /** {@code pod.service.namespace} — 3-сегментный prefix headless service / StatefulSet. */
    private static final int HEADLESS_SVC_PARTS = 3;

    /**
     * Валидатор hostname по RFC 1035 §2.3.1 (с расширением для IDN: точки разрешены
     * между метками). Применяется к извлечённым из exception message строкам перед
     * записью в {@code metadata.hostname} — отсекает зашумлённые фрагменты
     * (OS-сообщения, IP-литералы с двоеточиями, пробелы), которые могли бы
     * исказить структурированную диагностику в Loki/Elasticsearch.
     *
     * <p>Длина &le; 253 символа суммарно (RFC 1035 §2.3.4); каждая label —
     * буква/цифра/дефис, без ведущего/замыкающего дефиса.
     */
    private static final Pattern VALID_HOSTNAME_PATTERN =
            Pattern.compile("^(?=.{1,253}$)[a-zA-Z0-9]" +
                            "(?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
                            "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

    /**
     * Известные суффиксы JDK-сообщений {@link UnknownHostException}, которые
     * добавляются OS-резолвером после {@code "hostname:"}. Используется для
     * корректного выделения hostname на IPv6-адресах (где {@code :} —
     * часть адреса, а не разделитель).
     *
     * <p>Источники: glibc {@code getaddrinfo} ({@code "Name or service not known"},
     * {@code "Temporary failure in name resolution"}, {@code "Try again"}),
     * BSD/macOS ({@code "nodename nor servname provided"}), Windows
     * ({@code "No such host is known"}), JDK fallback ({@code "no such host"},
     * {@code "not known"}, {@code "unreachable"}).
     */
    private static final Pattern JDK_UHE_SUFFIX_PATTERN = Pattern.compile(
            ":\\s*(?:Name or service not known|" +
                    "no such host(?:\\s+is\\s+known)?|" +
                    "nodename nor servname(?:\\s+provided[^$]*)?|" +
                    "Temporary failure in name resolution|" +
                    "Try again|" +
                    "no address associated[^$]*|" +
                    "not known|" +
                    "(?:network is )?unreachable|" +
                    "(?:connection )?refused|" +
                    "timed out|timeout)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final ClassLoader LOADER = DnsResolutionExceptionStrategy.class.getClassLoader();

    // Netty DNS resolver classes

    @Nullable
    private static final Class<?> NETTY_DNS_RESOLVER_TIMEOUT =
            ReflectionUtils.loadOptionalClass("io.netty.resolver.dns.DnsNameResolverTimeoutException", LOADER);

    @Nullable
    private static final Class<?> NETTY_DNS_RESOLVER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("io.netty.resolver.dns.DnsNameResolverException", LOADER);

    /**
     * Netty 4.1.105+: обёртка DNS RCODE, устанавливается как cause {@code UnknownHostException}
     * или {@code DnsNameResolverException}. Содержит {@code io.netty.handler.codec.dns.DnsResponseCode}
     * (RFC 1035 §4.1.1), извлекаемый через метод {@code getCode()} (не {@code Integer},
     * а wrapper-объект — поэтому используется reflection-разворот через {@code intValue()}).
     */
    @Nullable
    private static final Class<?> NETTY_DNS_ERROR_CAUSE =
            ReflectionUtils.loadOptionalClass("io.netty.resolver.dns.DnsErrorCauseException", LOADER);

    // Kubernetes FQDN analysis

    /**
     * Kubernetes Service FQDN: {@code service.namespace.svc.cluster-domain}.
     * Группа 1 = "service.namespace" (до <b>первого</b> {@code .svc.}),
     * Группа 2 = cluster domain.
     *
     * <p>Reluctant {@code .+?} обеспечивает корректное разбиение для
     * federated-кластеров с вложенными {@code .svc.} (например,
     * {@code foo.svc.bar.svc.cluster.local}).
     */
    private static final Pattern K8S_SVC_PATTERN =
            Pattern.compile("^(.+?)\\.svc\\.(.+)$", Pattern.CASE_INSENSITIVE);

    /**
     * Anchor-вариант для проверки «hostname является K8s-внутренним FQDN».
     * Требует: {@code <something>.svc.<domain>.<tld>} — минимум один сегмент
     * после {@code .svc.}, отсекая ложные срабатывания на коммерческих именах
     * вида {@code fortnite.svc.example.com} (которые формально соответствуют
     * паттерну, но не являются K8s services — отличить их без contextual
     * lookup невозможно, поэтому полагаемся на консервативную эвристику:
     * заведомый K8s FQDN всегда оканчивается на {@code .svc.<root>.<tld>}).
     */
    private static final Pattern K8S_FQDN_ANCHOR =
            Pattern.compile(".+\\.svc\\.[a-zA-Z0-9-]+\\.[a-zA-Z0-9-]+$",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern TIMEOUT_MS_PATTERN =
            Pattern.compile("(\\d+)\\s*(?:ms|millis(?:econds?)?)", Pattern.CASE_INSENSITIVE);

    // DNS RCODE mapping (RFC 1035 §4.1.1, RFC 6895)

    private static final Map<Integer, String> DNS_RCODE_NAMES;

    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(0, "NOERROR");
        m.put(1, "FORMERR");
        m.put(2, "SERVFAIL");
        m.put(3, "NXDOMAIN");
        m.put(4, "NOTIMP");
        m.put(5, "REFUSED");
        m.put(6, "YXDOMAIN");
        m.put(7, "YXRRSET");
        m.put(8, "NXRRSET");
        m.put(9, "NOTAUTH");
        m.put(10, "NOTZONE");
        DNS_RCODE_NAMES = Collections.unmodifiableMap(m);
    }

    public DnsResolutionExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.DNS, stackHintExtractor);
    }

    /**
     * DNS-ошибки → {@link LogPolicy#ERROR}.
     *
     * <p>Каждая DNS-ошибка в production — потенциальный outage:
     * downstream-сервис недоступен, все запросы к нему будут падать.
     * В Kubernetes DNS-ошибки могут быть transient (CoreDNS restart),
     * но требуют немедленного внимания SRE для исключения persistent failure
     * (неверный hostname, удалённый Service, Network Policy).
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.ERROR;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return isDnsException(cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        DnsErrorType errorType = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (errorType.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(errorType.name())
                .domain("dns")
                .diagnosticMetadata(buildDiagnosticMetadata(cause, errorType))
                .build();
        details.add(info);

        return errorType.toResolution(details);
    }

    /**
     * Классифицирует DNS-ошибку.
     *
     * <p>Порядок проверок критичен:
     * {@code DnsNameResolverTimeoutException extends DnsNameResolverException},
     * поэтому timeout-подкласс проверяется <b>первым</b>, иначе он будет
     * проглочен более общим типом и потеряет специализированную диагностику
     * ({@code queryTimeoutMs}, {@code GATEWAY_TIMEOUT 504}).
     */
    @NonNull
    private static DnsErrorType classify(@NonNull Throwable cause) {
        if (ClassUtils.isInstanceOf(NETTY_DNS_RESOLVER_TIMEOUT, cause)) {
            return DnsErrorType.NETTY_DNS_TIMEOUT;
        }

        if (ClassUtils.isInstanceOf(NETTY_DNS_RESOLVER_EXCEPTION, cause)) {
            return DnsErrorType.NETTY_DNS_FAILED;
        }

        if (cause instanceof UnknownHostException) {
            return DnsErrorType.UNKNOWN_HOST;
        }

        if (cause instanceof UnresolvedAddressException) {
            return DnsErrorType.UNRESOLVED_ADDRESS;
        }

        return DnsErrorType.GENERIC_DNS;
    }

    private static boolean isDnsException(@NonNull Throwable cause) {
        if (cause instanceof UnknownHostException) {
            return true;
        }

        if (cause instanceof UnresolvedAddressException) {
            return true;
        }

        if (ClassUtils.isInstanceOf(NETTY_DNS_RESOLVER_EXCEPTION, cause)) {
            return true;
        }

        return ClassUtils.isInstanceOf(NETTY_DNS_RESOLVER_TIMEOUT, cause);
    }

    /**
     * Строит {@code diagnosticMetadata} для DNS-ошибки.
     *
     * <p>Контракт безопасности: в metadata попадают только структурированные
     * поля (hostname после RFC-валидации, классификации, RCODE, K8s-разбор).
     * Сообщение exception целиком не сохраняется — оно может содержать
     * resolver IP, search domain list, конфигурацию DNS-сервера.
     *
     * <p>Root cause обрабатывается через {@link CauseChainNavigator#findDeepestCause}:
     * записывается только {@code rootCauseClass} без сообщения — в DNS-стеке
     * глубинная причина это обычно {@code DnsErrorCauseException} или
     * {@code IOException} от Netty с сырым resolver address (CWE-209).
     */
    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull DnsErrorType type) {
        DiagnosticMetadataBuilder builder = diagnosticMetadataFor(cause);

        String hostname = extractHostname(cause);
        if (isValidHostname(hostname)) {
            builder.put("hostname", hostname);
            builder.put("hostnameType", classifyHostnameType(hostname));
            enrichKubernetesContext(hostname, builder);
        }

        builder.put("resolverType", detectResolverType(type));

        // dnsErrorCategory пишется только тогда, когда несёт информацию,
        // не дублирующую reason (см. enrichDnsErrorCategory)
        enrichDnsErrorCategory(cause, type, builder);

        switch (type) {
            case NETTY_DNS_TIMEOUT, NETTY_DNS_FAILED -> enrichNettyDnsDetails(cause, builder);
            case UNKNOWN_HOST, UNRESOLVED_ADDRESS -> enrichJdkDnsCacheConfig(builder);
            default -> { }
        }

        enrichCauseChain(cause, builder);

        return builder.withStackHint().build();
    }

    /**
     * Проверяет извлечённый hostname на соответствие RFC 1035.
     * Защищает от записи зашумлённых/случайных строк (например, осколков
     * OS-сообщений вида {@code nslookup}, IP-литералов с двоеточиями,
     * фрагментов с пробелами) в структурированную метадату.
     */
    private static boolean isValidHostname(@Nullable String hostname) {
        return StringUtils.hasText(hostname)
                && VALID_HOSTNAME_PATTERN.matcher(hostname).matches();
    }

    /**
     * <p><b>CWE-209:</b> извлекается <b>только hostname</b>, не полное сообщение.
     */
    @Nullable
    private static String extractHostname(@NonNull Throwable cause) {
        if (cause instanceof UnknownHostException) {
            return extractHostnameFromUnknownHost(cause.getMessage());
        }

        if (ClassUtils.isInstanceOf(NETTY_DNS_RESOLVER_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(NETTY_DNS_RESOLVER_TIMEOUT, cause)) {
            return extractHostnameFromNetty(cause);
        }

        if (cause instanceof UnresolvedAddressException) {
            Throwable nested = cause.getCause();
            if (nested instanceof UnknownHostException) {
                return extractHostnameFromUnknownHost(nested.getMessage());
            }
        }

        return null;
    }

    /**
     * Извлекает hostname из Netty-исключений резолвера.
     *
     * <p>Основной путь — {@code DnsNameResolverException.question().name()};
     * метод возвращает имя в каноническом DNS-формате (с trailing dot,
     * e.g. {@code "payment.svc.cluster.local."}), который мы нормализуем,
     * отбрасывая завершающую точку для согласованности с
     * {@link #extractHostnameFromUnknownHost}.</p>
     *
     * <p>Fallback на парсинг message используется только при экзотических
     * custom-resolver реализациях, у которых {@code question()} возвращает
     * {@code null}.</p>
     */
    @Nullable
    private static String extractHostnameFromNetty(@NonNull Throwable cause) {
        String[] hostname = {null};

        ReflectionUtils.invokeMethod(cause, "question", question -> {
            if (question != null) {
                ReflectionUtils.invokeMethod(question, "name", name -> {
                    if (name instanceof String s && StringUtils.hasText(s)) {
                        // DNS canonical form: "host.example.com." — убираем trailing dot
                        hostname[0] = s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
                    }
                });
            }
        });

        if (hostname[0] != null) {
            return hostname[0];
        }

        return extractHostnameFromNettyMessage(cause.getMessage());
    }

    /**
     * Извлекает hostname из сообщения {@link UnknownHostException}.
     *
     * <p>Сообщение JDK-резолвера может иметь несколько форматов:
     * <ul>
     *   <li>{@code "hostname"} — короткая форма ({@code new UnknownHostException("foo")}).</li>
     *   <li>{@code "hostname: <OS message>"} — JDK + glibc/musl/BSD/Windows
     *       (например, {@code "payment.svc: Name or service not known"}).</li>
     *   <li>{@code "[::1]: <OS message>"} / {@code "fe80::1: <OS message>"} —
     *       IPv6 literal с двоеточиями внутри hostname.</li>
     * </ul>
     *
     * <p>Простой {@code split(":")} ломается на IPv6 (двоеточие — часть адреса).
     * Поэтому отрезаем известные OS-суффиксы по {@link #JDK_UHE_SUFFIX_PATTERN};
     * остаток считается hostname-частью. Если суффикс не распознан и в строке
     * всего одно двоеточие — это, скорее всего, обычный {@code "host: msg"};
     * иначе (несколько двоеточий) — оставляем строку целиком, чтобы не сломать
     * IPv6 literal.
     */
    @Nullable
    private static String extractHostnameFromUnknownHost(@Nullable String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }

        // Шаг 1: попытка отрезать известный OS-суффикс
        Matcher suffixMatcher = JDK_UHE_SUFFIX_PATTERN.matcher(message);
        if (suffixMatcher.find()) {
            String hostname = message.substring(0, suffixMatcher.start()).trim();
            return StringUtils.hasText(hostname) ? hostname : null;
        }

        // Шаг 2: суффикс не распознан — пытаемся аккуратно разделить по последнему ": "
        // (двоеточие+пробел редко встречается в валидных hostname / IPv6-адресах)
        int sepIdx = message.indexOf(": ");
        if (sepIdx > 0) {
            String hostname = message.substring(0, sepIdx).trim();
            return StringUtils.hasText(hostname) ? hostname : null;
        }

        // Шаг 3: одиночное двоеточие без пробела + один сегмент — обычно "host:msg"
        // (но только для строк без двух и более двоеточий, чтобы не сломать IPv6)
        long colonCount = message.chars().filter(c -> c == ':').count();
        if (colonCount == 1) {
            int colonIdx = message.indexOf(':');
            String hostname = message.substring(0, colonIdx).trim();
            return StringUtils.hasText(hostname) ? hostname : null;
        }

        // Шаг 4: суффикс не отрезается, ":" → IPv6 — возвращаем строку целиком
        String trimmed = message.trim();
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    @Nullable
    private static String extractHostnameFromNettyMessage(@Nullable String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }

        int start = message.indexOf('\'');
        if (start < 0) {
            return null;
        }

        int end = message.indexOf('\'', start + 1);
        if (end <= start + 1) {
            return null;
        }

        String hostname = message.substring(start + 1, end);
        return StringUtils.hasText(hostname) ? hostname : null;
    }

    /**
     * Классифицирует hostname по типу:
     * <ul>
     *   <li>{@code K8S_INTERNAL} — Kubernetes Service FQDN
     *       ({@code <name>.svc.<domain>.<tld>}, чаще всего
     *       {@code *.svc.cluster.local}). Anchor-проверка через
     *       {@link #K8S_FQDN_ANCHOR} отсекает ложные срабатывания
     *       на коммерческих именах с подстрокой {@code .svc.}.</li>
     *   <li>{@code LOCAL} — mDNS / link-local ({@code *.local}).</li>
     *   <li>{@code UNQUALIFIED} — short name без точек (например, поиск
     *       идёт через DNS search domains).</li>
     *   <li>{@code EXTERNAL} — всё остальное.</li>
     * </ul>
     */
    @NonNull
    private static String classifyHostnameType(@NonNull String hostname) {
        String lower = hostname.toLowerCase(Locale.ROOT);
        if (K8S_FQDN_ANCHOR.matcher(lower).matches()) {
            return "K8S_INTERNAL";
        }

        if (lower.endsWith(".local")) {
            return "LOCAL";
        }

        if (!hostname.contains(".")) {
            return "UNQUALIFIED";
        }

        return "EXTERNAL";
    }

    /**
     * Извлекает Kubernetes Service и Namespace из FQDN.
     *
     * <p>Patterns:
     * <ul>
     *   <li>{@code service.namespace.svc.cluster.local}
     *       → {@code k8sServiceName=service}, {@code k8sNamespace=namespace}</li>
     *   <li>{@code pod.service.namespace.svc.cluster.local}
     *       → {@code k8sServiceName=service}, {@code k8sNamespace=namespace}
     *       (headless Service / StatefulSet)</li>
     *   <li>{@code subdomain.pod.service.namespace.svc.cluster.local}
     *       → берутся последние два сегмента до {@code .svc.} —
     *       это best-effort для нестандартных layout'ов.</li>
     * </ul>
     *
     * <p>Информация о принадлежности к K8s передаётся через {@code hostnameType=K8S_INTERNAL}
     * (см. {@link #classifyHostnameType}); отдельный boolean {@code isKubernetesInternal}
     * не дублируется во избежание противоречий.
     *
     * <p><b>CWE-209 trade-off:</b> namespace и cluster domain раскрываются.
     * Namespace — не секрет (виден в {@code kubectl get namespaces}).
     * Cluster domain ({@code cluster.local}) — стандартный default.
     */
    private static void enrichKubernetesContext(@NonNull String hostname,
                                                @NonNull DiagnosticMetadataBuilder builder) {
        Matcher matcher = K8S_SVC_PATTERN.matcher(hostname);
        if (!matcher.matches()) {
            return;
        }

        String beforeSvc = matcher.group(1);
        String[] parts = beforeSvc.split("\\.", -1);

        if (parts.length == STD_SVC_PARTS) {
            // service.namespace
            builder.put("k8sServiceName", parts[0]);
            builder.put("k8sNamespace", parts[1]);
        } else if (parts.length == HEADLESS_SVC_PARTS) {
            // pod.service.namespace (headless / StatefulSet)
            builder.put("k8sServiceName", parts[1]);
            builder.put("k8sNamespace", parts[2]);
        } else if (parts.length > HEADLESS_SVC_PARTS) {
            // Нестандартный layout — берём последние два сегмента как best-effort
            builder.put("k8sServiceName", parts[parts.length - 2]);
            builder.put("k8sNamespace", parts[parts.length - 1]);
        }
        // parts.length == 1 — не записываем ничего: один сегмент
        // не позволяет уверенно отличить service от namespace.
    }

    /**
     * Записывает {@code dnsErrorCategory} только когда оно несёт информацию,
     * не дублирующую {@code reason}:
     * <ul>
     *   <li>{@code NETTY_DNS_FAILED} → конкретный RCODE
     *       ({@code NXDOMAIN}/{@code SERVFAIL}/{@code REFUSED}/{@code RCODE_<n>}).
     *       Если RCODE недоступен — поле не пишется (вместо {@code RESOLUTION_FAILED},
     *       которое тождественно reason).</li>
     *   <li>{@code UNKNOWN_HOST} / {@code UNRESOLVED_ADDRESS} → разбор
     *       OS-сообщения JDK резолвера ({@code NXDOMAIN}/{@code SERVFAIL}/
     *       {@code TIMEOUT}/{@code REFUSED}/...). Если эвристика не сработала —
     *       поле не пишется.</li>
     *   <li>{@code NETTY_DNS_TIMEOUT}, {@code GENERIC_DNS} → не пишем,
     *       категория тождественна reason.</li>
     * </ul>
     */
    private static void enrichDnsErrorCategory(@NonNull Throwable cause,
                                               @NonNull DnsErrorType type,
                                               @NonNull DiagnosticMetadataBuilder builder) {
        switch (type) {
            case NETTY_DNS_FAILED -> {
                int rcode = extractDnsRcode(cause);
                if (rcode >= 0) {
                    String rcodeName = DNS_RCODE_NAMES.get(rcode);
                    builder.put("dnsErrorCategory",
                            (rcodeName != null) ? rcodeName : "RCODE_" + rcode);
                }
            }

            case UNKNOWN_HOST -> {
                String category = classifyJdkDnsCategory(cause.getMessage());
                if (category != null) {
                    builder.put("dnsErrorCategory", category);
                }
            }

            case UNRESOLVED_ADDRESS -> {
                Throwable nested = cause.getCause();
                if (nested instanceof UnknownHostException) {
                    String category = classifyJdkDnsCategory(nested.getMessage());
                    if (category != null) {
                        builder.put("dnsErrorCategory", category);
                    }
                }
            }

            default -> {
                // NETTY_DNS_TIMEOUT и GENERIC_DNS — категория тождественна reason
            }
        }
    }

    /**
     * Эвристическая категоризация {@link UnknownHostException} по тексту сообщения.
     * Сообщения OS-зависимы (Linux glibc, Alpine musl, macOS, Windows), поэтому
     * это <b>дополнительная</b> подсказка SRE, а не первичная классификация.
     *
     * @return категория или {@code null}, если эвристика не сработала
     */
    @Nullable
    private static String classifyJdkDnsCategory(@Nullable String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }

        String lower = message.toLowerCase(Locale.ROOT);

        if (lower.contains("name or service not known")
                || lower.contains("no such host")
                || lower.contains("not known")
                || lower.contains("no address associated")
                || lower.contains("nodename nor servname")) {
            return "NXDOMAIN";
        }

        if (lower.contains("temporary failure")
                || lower.contains("try again")) {
            return "SERVFAIL";
        }

        if (lower.contains("unreachable")
                || lower.contains("network is unreachable")) {
            return "NETWORK_UNREACHABLE";
        }

        if (lower.contains("timed out")
                || lower.contains("timeout")) {
            return "TIMEOUT";
        }

        if (lower.contains("connection refused")) {
            return "REFUSED";
        }

        return null;
    }

    @NonNull
    private static String detectResolverType(@NonNull DnsErrorType type) {
        return switch (type) {
            case NETTY_DNS_TIMEOUT, NETTY_DNS_FAILED -> "NETTY_ASYNC";
            case UNKNOWN_HOST -> "JDK";
            case UNRESOLVED_ADDRESS -> "NIO";
            case GENERIC_DNS -> "UNKNOWN_RESOLVER";
        };
    }

    /**
     * Извлекает DNS RCODE и question type из Netty resolver exceptions.
     *
     * <p>DNS RCODE (RFC 1035): из {@code DnsErrorCauseException.getCode()} в cause chain.
     * Доступно с Netty 4.1.105+ — без этого класса поле пропускается.</p>
     *
     * <p>Question type: из {@code DnsNameResolverException.question().type()}
     * — A, AAAA, SRV, CNAME (через {@code DnsRecordType.name()}).</p>
     */
    private static void enrichNettyDnsDetails(@NonNull Throwable cause,
                                              @NonNull DiagnosticMetadataBuilder builder) {
        int rcode = extractDnsRcode(cause);
        if (rcode >= 0) {
            builder.put("dnsRcode", String.valueOf(rcode));

            String rcodeName = DNS_RCODE_NAMES.get(rcode);
            if (rcodeName != null) {
                builder.put("dnsRcodeName", rcodeName);
            }
        }

        ReflectionUtils.invokeMethod(cause, "question", question -> {
            if (question == null) {
                return;
            }

            ReflectionUtils.invokeMethod(question, "type", questionType -> {
                if (questionType == null) {
                    return;
                }

                // Предпочтительно: DnsRecordType.name() → "A"/"AAAA"/"SRV"
                // (короткое имя для group-by в Grafana/Loki).
                // Fallback на toString() возвращает "A(1)" — менее удобно,
                // но допустимо при экзотических custom types.
                String[] typeName = {null};
                ReflectionUtils.invokeMethod(questionType, "name", n -> {
                    if (n instanceof String s && StringUtils.hasText(s)) {
                        typeName[0] = s;
                    }
                });

                if (typeName[0] != null) {
                    builder.put("dnsQuestionType", typeName[0]);
                } else {
                    String fallback = questionType.toString();
                    if (StringUtils.hasText(fallback)) {
                        builder.put("dnsQuestionType", fallback);
                    }
                }
            });
        });

        if (ClassUtils.isInstanceOf(NETTY_DNS_RESOLVER_TIMEOUT, cause)) {
            String msg = cause.getMessage();
            if (msg != null) {
                Matcher m = TIMEOUT_MS_PATTERN.matcher(msg);
                if (m.find()) {
                    builder.put("queryTimeoutMs", m.group(1));
                }
            }
        }
    }

    /**
     * Ищет {@code DnsErrorCauseException} (Netty 4.1.105+) в cause chain
     * и извлекает DNS RCODE.
     *
     * <p>Netty устанавливает {@code DnsErrorCauseException} как cause
     * для {@code UnknownHostException} или {@code DnsNameResolverException},
     * когда DNS-сервер возвращает non-zero RCODE.</p>
     *
     * <p>Метод {@code DnsErrorCauseException.getCode()} возвращает
     * {@code io.netty.handler.codec.dns.DnsResponseCode} — wrapper-объект,
     * а не {@code Integer}. Поэтому сначала пробуем прямой instanceof
     * (на случай будущих API-изменений), затем разворачиваем wrapper
     * через reflection-вызов {@code intValue()}.
     *
     * <p>Обход cause chain идёт через {@link CauseChainNavigator} —
     * это даёт защиту от циклов через {@code IdentityHashMap}, в отличие
     * от ручного {@code while (current != null)} с проверкой только
     * self-loop {@code next == current}.
     *
     * @return DNS RCODE (0–15) или {@code -1} если не найден
     */
    private static int extractDnsRcode(@NonNull Throwable throwable) {
        if (NETTY_DNS_ERROR_CAUSE == null) {
            return -1;
        }

        @SuppressWarnings("unchecked")
        Throwable errorCause = CauseChainNavigator.findCause(
                throwable,
                (Class<Throwable>) NETTY_DNS_ERROR_CAUSE,
                CAUSE_CHAIN_DEPTH);
        if (errorCause == null) {
            return -1;
        }

        int[] code = {-1};
        // Текущий API Netty (4.1.105+): метод называется getCode() и возвращает
        // DnsResponseCode (wrapper-объект), а не Integer. Разворачиваем через intValue().
        ReflectionUtils.invokeMethod(errorCause, "getCode", val -> {
            if (val instanceof Integer c) {
                // Гипотетический будущий API, отдающий int напрямую — поддерживаем заранее
                code[0] = c;
                return;
            }

            if (val != null) {
                ReflectionUtils.invokeMethod(val, "intValue", iv -> {
                    if (iv instanceof Integer c) {
                        code[0] = c;
                    }
                });
            }
        });

        return code[0];
    }

    /**
     * JVM DNS cache configuration — критичный диагностический сигнал.
     *
     * <p>{@code networkaddress.cache.ttl} управляет кэшированием успешных
     * DNS-запросов в {@code InetAddress}:
     * <ul>
     *   <li>{@code -1} → кэшировать на время работы JVM (Java &lt; 8 default) —
     *       опасно в K8s: после миграции pod на новый IP клиент будет ходить
     *       в старый адрес до перезапуска</li>
     *   <li>{@code 30} → default Java 8+ при наличии SecurityManager</li>
     *   <li>{@code 0} → не кэшировать (нагрузка на CoreDNS)</li>
     * </ul>
     *
     * <p>{@code networkaddress.cache.negative.ttl} управляет кэшированием
     * неудачных DNS-запросов:
     * <ul>
     *   <li>{@code -1} → кэшировать бесконечно (Java &lt; 12 default!) — опасно в K8s:
     *       после рестарта/деплоя Service NXDOMAIN закэшируется навсегда</li>
     *   <li>{@code 0} → не кэшировать — каждый failed lookup → DNS-запрос (нагрузка на CoreDNS)</li>
     *   <li>{@code 10} → default в Java 12+ (10 секунд)</li>
     * </ul>
     *
     * <p>{@code SecurityException} при наличии SecurityManager — пропускаем.
     */
    private static void enrichJdkDnsCacheConfig(@NonNull DiagnosticMetadataBuilder builder) {
        try {
            String positiveTtl = java.security.Security.getProperty("networkaddress.cache.ttl");
            if (positiveTtl != null) {
                builder.put("jdkDnsPositiveCacheTtlSec", positiveTtl);
            }

            String negativeTtl = java.security.Security.getProperty("networkaddress.cache.negative.ttl");
            if (negativeTtl != null) {
                builder.put("jdkDnsNegativeCacheTtlSec", negativeTtl);
            }
        } catch (SecurityException ignored) {
            // SecurityManager ограничивает доступ к Security properties
        }
    }

    private static void enrichCauseChain(@NonNull Throwable throwable,
                                         @NonNull DiagnosticMetadataBuilder builder) {
        Throwable root = CauseChainNavigator.findDeepestCause(throwable, CAUSE_CHAIN_DEPTH);
        if (root != throwable) {
            builder.put("rootCauseClass", root.getClass().getName());
        }
    }

    private enum DnsErrorType implements ErrorClassification {
        UNKNOWN_HOST(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The DNS name of the target service could not be resolved. "
                        + "The service may not exist, or the DNS infrastructure "
                        + "is temporarily unavailable."
        ),

        UNRESOLVED_ADDRESS(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The network address of the target service could not be resolved. "
                        + "DNS resolution failed before a connection could be established."
        ),

        NETTY_DNS_TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "The DNS resolution timed out. The DNS server did not respond "
                        + "within the configured query timeout. "
                        + "The DNS infrastructure may be overloaded."
        ),

        NETTY_DNS_FAILED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The Netty DNS resolver failed to resolve the target hostname. "
                        + "The DNS server may be unreachable or returned an error response."
        ),

        GENERIC_DNS(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "DNS resolution failed for the target service. "
                        + "The service address could not be determined."
        );

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        DnsErrorType(@NonNull HttpStatus httpStatus,
                     @NonNull Retryability retryability,
                     @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @Override
        @NonNull
        public HttpStatus httpStatus() { return httpStatus; }

        @Override
        @NonNull
        public Retryability retryability() { return retryability; }

        @Override
        @NonNull
        public String message() { return message; }
    }
}
