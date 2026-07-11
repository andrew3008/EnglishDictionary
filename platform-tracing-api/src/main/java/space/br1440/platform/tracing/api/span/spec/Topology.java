package space.br1440.platform.tracing.api.span.spec;

/**
 * Топология (вид) связи нового span'а с уже активным span'ом в момент старта.
 */
public enum Topology {
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
