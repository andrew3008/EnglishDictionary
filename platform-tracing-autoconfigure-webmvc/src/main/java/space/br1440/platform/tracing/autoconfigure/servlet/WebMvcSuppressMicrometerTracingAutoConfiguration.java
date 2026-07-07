package space.br1440.platform.tracing.autoconfigure.servlet;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.support.OtelAgentDetector;

/**
 * Подавление дублирующих HTTP-обсерваций {@code Micrometer} для Servlet-стека (Spring MVC).
 * <p>
 * Активируется при {@code platform.tracing.suppression.suppress-micrometer-tracing=true} и
 * регистрирует {@link ObservationRegistryCustomizer}, который через {@code ObservationPredicate}
 * проверяет тип контекста обсервации по {@code instanceof}: Servlet-flavor
 * {@link ServerRequestObservationContext} (Spring Web, серверная сторона) и
 * {@link ClientRequestObservationContext} (RestClient/RestTemplate, клиентская сторона).
 * <p>
 * Используется при работе с OpenTelemetry Java Agent: Agent самостоятельно создаёт HTTP-span'ы на
 * уровне Tomcat и HTTP-клиентов, поэтому Micrometer-обсервации становятся лишними. Подавление
 * предотвращает дублирование span'ов в backend'е.
 *
 * <h3>Trade-off (W1, W4)</h3>
 * {@code ObservationPredicate} → {@code false} превращает обсервацию в {@code Observation.NOOP}:
 * не вызываются ни tracing-, ни metrics-{@code ObservationHandler}'ы. Это означает, что метрики
 * {@code http.server.requests} / {@code http.client.requests}, идущие через тот же
 * Observation-pipeline, тоже исчезают. Метрики HTTP при этом должны поступать из OpenTelemetry
 * Java Agent (OTel metrics pipeline). Если нужны метрики без spans без Agent — используйте
 * собственный {@code ObservationConvention} + selective handler, а не глобальный predicate.
 *
 * <h3>Почему именно {@link AutoConfiguration} (W5, C7)</h3>
 * <ol>
 *   <li>Аннотация устанавливает {@code proxyBeanMethods=false} — нет CGLIB-proxy на класс,
 *       экономия 500–1000 ms cold start и 5–10 MB памяти на типичный Spring Boot AC-граф;</li>
 *   <li>корректная регистрация в реестре {@code META-INF/spring/...AutoConfiguration.imports}
 *       и работа {@code @AutoConfigureAfter}/{@code @AutoConfigureBefore};</li>
 *   <li>семантика — это именно auto-config, а не пользовательский {@code @Configuration}.</li>
 * </ol>
 * {@code @AutoConfigureAfter(ObservationAutoConfiguration.class)} гарантирует, что наш
 * {@link ObservationRegistryCustomizer} будет применён после полной инициализации
 * {@code ObservationRegistry} (handlers / predicates) — иначе порядок применения customizer'ов
 * не определён.
 */
@AutoConfiguration(after = ObservationAutoConfiguration.class)
@AutoConfigureAfter(ObservationAutoConfiguration.class)
@ConditionalOnClass(ServerRequestObservationContext.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = TracingProperties.PREFIX + ".suppression",
        name = "suppress-micrometer-tracing",
        havingValue = "true")
public class WebMvcSuppressMicrometerTracingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebMvcSuppressMicrometerTracingAutoConfiguration.class);

    /**
     * {@link ObservationRegistryCustomizer}, подавляющий Servlet-flavor HTTP-обсервации через
     * {@code instanceof}-проверку класса контекста.
     * <p>
     * {@code @ConditionalOnMissingBean(name = ...)} (C6): если потребитель определил собственный
     * customizer с тем же именем, он перекроет наш и платформа не зарегистрирует второй.
     */
    @Bean("platformMvcHttpObservationSuppressor")
    @ConditionalOnMissingBean(name = "platformMvcHttpObservationSuppressor")
    public ObservationRegistryCustomizer<ObservationRegistry> platformMvcHttpObservationSuppressor() {
        return registry -> {
            registry.observationConfig().observationPredicate((name, context) -> {
                // instanceof по runtime-классу контекста: подавляем серверные и клиентские HTTP-обсервации
                // именно Servlet-варианта (Spring MVC). Reactive-flavor подавляется в WebFlux-модуле.
                if (context instanceof ServerRequestObservationContext
                        || context instanceof ClientRequestObservationContext) {
                    return false;
                }
                return true;
            });
            log.info("Подавлены Servlet HTTP server/client Micrometer Observation'ы — span'ы и метрики "
                    + "по этим observation'ам не публикуются. HTTP-телеметрия должна поступать из "
                    + "OpenTelemetry Java Agent. (agentDetected={})", OtelAgentDetector.isAgentPresent());
        };
    }
}
