package space.br1440.platform.tracing.autoconfigure.reactive;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import space.br1440.platform.tracing.autoconfigure.agent.OtelAgentDetector;
import space.br1440.platform.tracing.autoconfigure.integrations.observation.SuppressMicrometerTracingCondition;

/**
 * Подавление дублирующих HTTP-обсерваций {@code Micrometer} для реактивного стека (Spring WebFlux).
 * <p>
 * Активируется через {@code SuppressMicrometerTracingCondition}: если оператор не задал
 * {@code platform.tracing.suppression.suppress-micrometer-tracing} явно, значение auto-derive'ится
 * из присутствия OpenTelemetry Java Agent в JVM — типовому сервису не нужно вручную подбирать этот
 * флаг. При активации регистрирует {@link ObservationRegistryCustomizer}, который через
 * {@code ObservationPredicate}
 * проверяет тип контекста обсервации по {@code instanceof}: реактивный
 * {@link ServerRequestObservationContext} (пакет {@code http.server.reactive.observation}) и
 * {@link ClientRequestObservationContext} (пакет {@code web.reactive.function.client} — для
 * {@code WebClient}).
 * <p>
 * Используется при работе с OpenTelemetry Java Agent, который самостоятельно создаёт HTTP-span'ы
 * на уровне Netty и реактивных HTTP-клиентов.
 *
 * <h3>Trade-off (W1, W4)</h3>
 * {@code ObservationPredicate} → {@code false} превращает обсервацию в {@code Observation.NOOP}:
 * не вызываются ни tracing-, ни metrics-{@code ObservationHandler}'ы. Это означает, что метрики
 * {@code http.server.requests} / {@code http.client.requests}, идущие через тот же
 * Observation-pipeline, тоже исчезают. Метрики HTTP при этом должны поступать из OpenTelemetry
 * Java Agent (OTel metrics pipeline).
 *
 * <h3>Почему именно {@link AutoConfiguration} (W5, C7)</h3>
 * См. Javadoc {@code WebMvcSuppressMicrometerTracingAutoConfiguration} — обоснование выбора
 * {@link AutoConfiguration} (proxyBeanMethods=false, корректная регистрация в реестре,
 * семантика auto-config) и {@code @AutoConfigureAfter(ObservationAutoConfiguration.class)}
 * (порядок применения customizer'ов).
 */
@AutoConfiguration(after = ObservationAutoConfiguration.class)
@AutoConfigureAfter(ObservationAutoConfiguration.class)
@ConditionalOnClass(ServerRequestObservationContext.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@Conditional(SuppressMicrometerTracingCondition.class)
public class WebFluxSuppressMicrometerTracingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebFluxSuppressMicrometerTracingAutoConfiguration.class);

    /**
     * {@link ObservationRegistryCustomizer}, подавляющий реактивные HTTP-обсервации через
     * {@code instanceof}-проверку класса контекста.
     * <p>
     * {@code @ConditionalOnMissingBean(name = ...)} (C6): если потребитель определил собственный
     * customizer с тем же именем, он перекроет наш и платформа не зарегистрирует второй.
     */
    @Bean("platformWebFluxHttpObservationSuppressor")
    @ConditionalOnMissingBean(name = "platformWebFluxHttpObservationSuppressor")
    public ObservationRegistryCustomizer<ObservationRegistry> platformWebFluxHttpObservationSuppressor() {
        return registry -> {
            registry.observationConfig().observationPredicate((name, context) -> {
                // instanceof по runtime-классу: подавляем именно reactive-варианты HTTP-обсерваций.
                // FQCN reactive-классов отличается от Servlet-flavor — поэтому Servlet-обсервации
                // в reactive-приложении не возникают, а если возникнут (через @ConditionalOnClass)
                // — будут подавлены парным WebMvc-предикатом, активным только в Servlet-стеке.
                if (context instanceof ServerRequestObservationContext
                        || context instanceof ClientRequestObservationContext) {
                    return false;
                }
                return true;
            });
            log.info("Suppressed Reactive HTTP server/client Micrometer Observations — spans and metrics "
                    + "for these observations are not published. HTTP telemetry should come from "
                    + "OpenTelemetry Java Agent. (agentDetected={})", OtelAgentDetector.isAgentPresent());
        };
    }
}
