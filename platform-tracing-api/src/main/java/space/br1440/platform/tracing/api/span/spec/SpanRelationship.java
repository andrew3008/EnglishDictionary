package space.br1440.platform.tracing.api.span.spec;

/**
 * Связь (relationship) нового span'а с текущим активным span'ом или родительским trace-контекстом
 * в момент старта.
 * <p>
 * Это не OpenTelemetry {@code SpanKind}; протокольный client/server kind выводится отдельно
 * из {@link space.br1440.platform.tracing.api.span.SpanCategory}.
 */
public enum SpanRelationship {
    /**
     * Новый span станет дочерним к текущему активному span'у.
     */
    CHILD,

    /**
     * Новый span начнет новый trace и не будет дочерним к текущему активному span'у.
     */
    ROOT,

    /**
     * Новый span будет запущен отдельно от текущего активного span'а и без ссылок на другие span'ы.
     */
    DETACHED
}
