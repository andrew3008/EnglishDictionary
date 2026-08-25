package space.br1440.platform.tracing.api.attributes;

import java.util.Set;

/**
 * Канонические значения атрибута {@code platform.sampling.reason}
 * ({@link PlatformAttributes#PLATFORM_SAMPLING_REASON}).
 * <p>
 * Production-контракт SDK ↔ Collector: политики {@code tail_sampling} в YAML-конфигурациях Collector'а
 * обязаны ссылаться только на значения из множества {@link #EXPORTED}.
 */
public final class PlatformSamplingReasons {

    // *********************************************************************************************
    // RECORD_AND_SAMPLE: span экспортируется, значение видно политикам Collector'а
    // *********************************************************************************************

    /** Принудительная запись по заголовку {@code X-Trace-On} ({@code ForceHeaderRule}). */
    public static final String FORCE_HEADER = "force_header";

    /** QA-трассировка по заголовку {@code X-QA-Trace} ({@code QaTraceRule}). */
    public static final String QA_TRACE = "qa_trace";

    /** Родительский span сэмплирован — уважаем upstream-решение ({@code ParentDecisionRule}). */
    public static final String PARENT_SAMPLED = "parent_sampled";

    /** Сэмплирован по route-специфичному ratio ({@code RouteRatioRule}). */
    public static final String ROUTE_RATIO = "route_ratio";

    /** Сэмплирован по глобальному ratio ({@code DefaultRatioRule}). */
    public static final String GLOBAL_RATIO = "global_ratio";


    // *********************************************************************************************
    // DROP: span НЕ экспортируется; в Collector-политиках использовать НЕЛЬЗЯ
    // *********************************************************************************************

    /** Родительский span не сэмплирован ({@code ParentDecisionRule}). */
    public static final String PARENT_DROP = "parent_drop";

    /** Отброшен route-специфичным ratio ({@code RouteRatioRule}). */
    public static final String ROUTE_RATIO_DROP = "route_ratio_drop";

    /** Отброшен глобальным ratio ({@code DefaultRatioRule}). */
    public static final String GLOBAL_RATIO_DROP = "global_ratio_drop";

    /** Отброшен по drop-paths ({@code HardDropRule}). */
    public static final String DROP_PATH = "drop_path";


    // *********************************************************************************************
    // Метрические причины: решение принято, но атрибут на span не проставляется
    // *********************************************************************************************

    /** Kill-switch выключил трассировку ({@code KillSwitchRule}); только метрики. */
    public static final String KILL_SWITCH = "kill_switch";

    /** Fallback-DROP композитного сэмплера, ни одно правило не сработало; только метрики. */
    public static final String FALLBACK_DROP = "fallback_drop";

    /**
     * Множество значений, наблюдаемых Collector'ом на экспортируемых span'ах.
     * Только эти значения допустимы в {@code string_attribute}-политиках tail_sampling.
     */
    public static final Set<String> EXPORTED = Set.of(FORCE_HEADER, QA_TRACE, PARENT_SAMPLED, ROUTE_RATIO, GLOBAL_RATIO);

    /**
     * Множество DROP-значений: span с таким решением не покидает процесс,
     * Collector эти значения никогда не видит.
     */
    public static final Set<String> DROPPED = Set.of(PARENT_DROP, ROUTE_RATIO_DROP, GLOBAL_RATIO_DROP, DROP_PATH);

}
