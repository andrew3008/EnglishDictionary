package space.br1440.platform.errorhandling.extension.order;

import lombok.experimental.UtilityClass;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;

/**
 * Константы приоритетов для {@link TopExceptionHandlingStrategy}.
 *
 * <p>Меньшее значение — более высокий приоритет. Между платформенными
 * константами оставлены интервалы для пользовательских стратегий.
 *
 * <p>Пример использования в микросервисе:
 * <pre>{@code
 * @Override
 * public int getOrder() {
 *     return TopExceptionStrategyOrder.SECURITY + 10; // после SpringSecurity, до RateLimit
 * }
 * }</pre>
 */
@UtilityClass
public final class TopExceptionStrategyOrder {

    /**
     * Бизнес-исключения сервиса
     * ({@link space.br1440.platform.errorhandling.exception.business.BusinessException}
     * и его наследники).
     *
     * <p>Обрабатываются первыми, поскольку это типизированные платформенные
     * исключения с заранее известной структурой {@code ErrorEntry} — их не нужно
     * прогонять через security/validation классификацию.
     * Пользовательские доменные стратегии: {@code BUSINESS + N} (101–149).
     *
     * <p>Прочие наследники {@code AbstractErrorResponseException} (валидация, datasource,
     * inconsistency и т.п.) не относятся к bucket'у {@code BUSINESS}: на top-уровне они
     * проваливаются в {@code DefaultUnknownExceptionHandler}, а как {@code cause} —
     * подхватываются {@code ErrorEntryCauseStrategy} в cause-слое.
     */
    public static final int BUSINESS = 100;

    /**
     * OAuth2 client / resource server ({@code OAuth2AuthenticationException}).
     *
     * <p>После {@link #BUSINESS} (100), перед {@link #SECURITY} (200).
     * {@code OAuth2AuthenticationException} является подклассом
     * {@code AuthenticationException} Spring Security, поэтому {@code OAUTH2 < SECURITY}
     * гарантирует, что OAuth2-специфичная диагностика (коды ошибок, scopes, client_id)
     * выполняется здесь, а не поглощается generic 401/403 в
     * {@code SpringSecurityExceptionStrategy}.
     */
    public static final int OAUTH2 = 150;

    /**
     * Spring Security ({@code AuthenticationException}, {@code AccessDeniedException}).
     *
     * <p>После {@link #OAUTH2} (150) — generic 401/403 идут после OAuth2-специфичной
     * диагностики. Security раньше validation (400): статусы 401/403 не должны
     * маскироваться валидационными ошибками.
     */
    public static final int SECURITY = 200;

    /**
     * Rate limiting / Resilience4j ({@code RequestNotPermitted},
     * {@code RateLimitExceededException}).
     * После {@link #SECURITY} (200), перед {@link #VALIDATION} (400).
     */
    public static final int RATE_LIMIT = 300;

    /**
     * Bean Validation / MVC validation ({@code MethodArgumentNotValidException},
     * {@code BindException} и т.п.).
     * После {@link #RATE_LIMIT} (300): rate-limit ошибки имеют приоритет над
     * валидационными для корректного HTTP-семантики (429 vs 400).
     */
    public static final int VALIDATION = 400;

    /**
     * Spring MVC / Spring WebFlux framework исключения
     * ({@code NoHandlerFoundException}, {@code HttpRequestMethodNotSupportedException} и т.п.).
     * После {@link #VALIDATION} (400) — framework-ошибки маршрутизации и протокола
     * идут после прикладной валидации.
     */
    public static final int FRAMEWORK = 500;

    /**
     * Feign client.
     */
    public static final int FEIGN = 600;

    /**
     * Reactive WebClient.
     */
    public static final int WEBFLUX = 625;

    /**
     * Kafka client/broker.
     */
    public static final int KAFKA = 675;

    /**
     * gRPC client (upstream).
     */
    public static final int GRPC = 700;

    /**
     * gRPC server — pass-through explicit {@code StatusRuntimeException} from service code.
     */
    public static final int GRPC_SERVER = 710;

    /**
     * Jackson serialization/deserialization.
     */
    public static final int JACKSON = 800;

    /**
     * Fallback по {@code @ResponseStatus} / status resolver.
     * Предпоследний уровень: обрабатывает исключения с явно указанным HTTP-статусом,
     * которые не были перехвачены более специфичными стратегиями.
     */
    public static final int STATUS_FALLBACK = 900;

    /**
     * Неклассифицированные исключения — последний рубеж.
     *
     * <p>Используется {@link UnknownExceptionHandler} (отдельный SPI,
     * вызывается после исчерпания всех {@code TopExceptionHandlingStrategy}).
     * Значение зарезервировано для документации: пользователь может встать
     * перед обработчиком неклассифицированных исключений через
     * {@code UNCLASSIFIED - N}.
     */
    public static final int UNCLASSIFIED = Integer.MAX_VALUE - 100;

}
