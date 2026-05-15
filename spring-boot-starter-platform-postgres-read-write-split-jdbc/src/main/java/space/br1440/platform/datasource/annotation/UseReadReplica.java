package space.br1440.platform.datasource.annotation;

import space.br1440.platform.datasource.routing.DataSourceType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Маркер CQRS-маршрутизации для нетранзакционных чтений.
 * <p>
 * Интерпретация аннотации зависит от активного транспорт-стартера:
 * <ul>
 *   <li><b>JDBC-стартер</b> (hikaricp-jdbc): обрабатывается синхронным AOP-аспектом,
 *       который помещает {@link space.br1440.platform.datasource.routing.DataSourceType#READ}
 *       в ThreadLocal на время выполнения метода.</li>
 *   <li><b>R2DBC-стартер</b> (jooq-r2dbc): обрабатывается реактивным AOP-аспектом,
 *       который делает {@code Mono/Flux.contextWrite(DataSourceType.READ)} — интент
 *       пропагируется через Reactor Context.</li>
 * </ul>
 * Физика чтения (только Secondary или балансировка между всеми pg-нодами) определяется
 * параметром {@code platform.datasource.postgres-cluster.read-replica.strategy} и живёт в topology-слое
 * (pg-specific). Сама аннотация живёт в intent-слое и не знает про pg-топологию.
 *
 * <h3>Взаимодействие с {@code @Transactional}</h3>
 * Комбинирование {@code @UseReadReplica} и {@code @Transactional} на одном методе
 * <b>запрещено</b> — сочетание неоднозначно, так как оба механизма влияют на выбор
 * соединения. При обнаружении сочетания:
 * <ul>
 *   <li>JDBC-стартер: стартап падает с {@code IllegalStateException} через
 *       {@code RoutingAnnotationConflictValidator}.</li>
 *   <li>R2DBC-стартер: поведение совпадает — сочетание запрещено; {@code @Transactional}
 *       выигрывает, а об конфликте сообщается в лог как WARN.</li>
 * </ul>
 * Для read-only транзакций используйте {@code @Transactional(readOnly = true)} — это
 * канонический способ выразить намерение чтения одновременно с транзакционными границами.
 *
 * @apiNote Аннотация runtime-retention: обработчики (AOP-аспекты) читают её через
 *     {@code AnnotationUtils.findAnnotation(method, UseReadReplica.class)}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UseReadReplica {
}
