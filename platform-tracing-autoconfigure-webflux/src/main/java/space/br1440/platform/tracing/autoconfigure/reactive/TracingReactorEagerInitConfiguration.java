package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Hooks;

/**
 * Принудительная eager-инициализация {@link ReactorAutoConfiguration} в реактивных приложениях.
 * <p>
 * <b>Назначение.</b> Спринг Бут включает свойство {@code spring.reactor.context-propagation=AUTO},
 * которое активирует автоматический проброс trace-контекста (W3C MDC ключи {@code traceId} /
 * {@code spanId}) через цепочки {@code Mono} / {@code Flux} с помощью
 * {@code Hooks.enableAutomaticContextPropagation()}. Эта операция выполняется именно в
 * {@link ReactorAutoConfiguration} при создании контекста.
 * <p>
 * <b>Проблема.</b> При включённой ленивой инициализации ({@code spring.main.lazy-initialization=true},
 * — частая практика для ускорения старта в Helm-чартах) {@link ReactorAutoConfiguration} не
 * инстанциируется до первого обращения к её бинам, и хук {@code Hooks.enableAutomaticContextPropagation}
 * не вызывается на старте приложения. В результате trace-контекст теряется в реактивных
 * цепочках, а MDC {@code traceId} в логах оказывается пустым. Это известный открытый баг
 * Spring Boot 3.5.
 * <p>
 * <b>Решение.</b> Регистрация {@link LazyInitializationExcludeFilter}, который явно исключает
 * {@link ReactorAutoConfiguration} из числа лениво инициализируемых. Это безопасно: класс
 * крошечный, его инициализация не имеет побочных эффектов, кроме регистрации Reactor-хуков.
 * <p>
 * <b>Условия активации.</b>
 * <ul>
 *     <li>Приложение реактивное ({@link ConditionalOnWebApplication.Type#REACTIVE});</li>
 *     <li>В classpath присутствуют {@link Hooks}, {@link LazyInitializationExcludeFilter} и
 *         {@link ReactorAutoConfiguration}.</li>
 * </ul>
 * Если хотя бы одно условие не выполнено, конфигурация игнорируется без последствий.
 *
 * @see <a href="https://github.com/spring-projects/spring-boot/issues/40180">spring-boot#40180</a>
 */
@AutoConfiguration
@ConditionalOnClass({Hooks.class, LazyInitializationExcludeFilter.class, ReactorAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class TracingReactorEagerInitConfiguration {

    /**
     * Исключает {@link ReactorAutoConfiguration} из ленивой инициализации, чтобы хук
     * {@code Hooks.enableAutomaticContextPropagation()} вызвался на старте JVM, а не при
     * первом запросе.
     */
    @Bean
    public LazyInitializationExcludeFilter platformTracingEagerReactorAutoConfigurationFilter() {
        return LazyInitializationExcludeFilter.forBeanTypes(ReactorAutoConfiguration.class);
    }

    /**
     * WARN, если {@code spring.reactor.context-propagation} не AUTO — риск потери traceId на scheduler hop.
     */
    @Bean
    TracingReactorContextPropagationStartupRunner platformTracingReactorContextPropagationStartupRunner(
            Environment environment) {
        return new TracingReactorContextPropagationStartupRunner(environment);
    }
}
