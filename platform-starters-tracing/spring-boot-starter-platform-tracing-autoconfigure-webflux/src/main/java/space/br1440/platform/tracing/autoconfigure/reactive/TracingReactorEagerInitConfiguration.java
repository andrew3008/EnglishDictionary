package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.context.annotation.PropertySource;

import reactor.core.publisher.Hooks;

/**
 * Задаёт {@code spring.reactor.context-propagation=AUTO}.
 * Исключение {@link ReactorAutoConfiguration} из lazy-init принадлежит Spring Boot 3.5
 * ({@code reactorAutoConfigurationLazyInitializationExcludeFilter}).
 */
@AutoConfiguration
@ConditionalOnClass({Hooks.class, ReactorAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@PropertySource("classpath:META-INF/platform-tracing-reactor-defaults.properties")
public class TracingReactorEagerInitConfiguration {
}
