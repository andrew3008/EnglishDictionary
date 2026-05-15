package space.br1440.platform.metrics;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class MetricTags {

    // ========================================================================================
    // Общие и HTTP
    // ========================================================================================
    public static final String METHOD = "method";
    public static final String PATH = "path";
    public static final String STATUS = "status";

    // ========================================================================================
    // Kafka
    // ========================================================================================
    public static final String TOPIC = "topic";
    public static final String CLIENT_ID = "client_id";
    public static final String CONSUMER_GROUP = "consumer_group";

    /**
     * Тег результата обработки для {@code platform_consumer_processing_seconds} / batch-таймеров:
     * {@link #OUTCOME_SUCCESS} или {@link #OUTCOME_ERROR}.
     */
    public static final String OUTCOME = "outcome";

    /** Тег с FQN-именем класса исключения; при успехе — {@link #EXCEPTION_NONE}. */
    public static final String EXCEPTION_CLASS = "exception_class";

    /**
     * Лейбл результата для ADR-счётчиков {@code *_messages_total} (отдельно от {@link #OUTCOME} у таймеров):
     * {@link #RESULT_SUCCESS} / {@link #RESULT_FAILURE}.
     */
    public static final String RESULT = "result";

    // ========================================================================================
    // gRPC
    // ========================================================================================
    public static final String RPC_METHOD = "rpc_method";
    public static final String RPC_STATUS_CODE = "rpc_status_code";
    public static final String SERVICE = "service";

    // ========================================================================================
    // Стандартные значения тегов
    // ========================================================================================

    /** Возможные значения тега {@link #OUTCOME} для таймеров обработки. */
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";

    /** Значение тега {@link #EXCEPTION_CLASS} при успешной обработке. */
    public static final String EXCEPTION_NONE = "none";

}
