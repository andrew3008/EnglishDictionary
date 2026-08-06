package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.ReplaceSamplingRouteRatiosRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetDiagnosticsLogLevelRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetExporterEnabledRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetSamplingEnabledRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetSamplingRatioRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetTraceControlHeadersEnabledRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetValidationEnabledRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetValidationStrictRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyMutationService;

import java.util.Map;
import java.util.function.Function;

/**
 * Registers the WebFlux management write surface for the eight typed DEV_ACTUATOR
 * operations. Discovered via this module's
 * {@code META-INF/spring/…ManagementContextConfiguration.imports}.
 * <p>
 * Transport-only: all mutations delegate to
 * {@link TracingPolicyMutationService}. No second command service, DTO model,
 * readiness probe, or private-JMX client.
 * <p>
 * Stack isolation: lives in {@code platform-tracing-autoconfigure-webflux} so Servlet
 * applications do not compile against WebFlux types. Activation requires both
 * {@link DispatcherHandler} on the classpath and a reactive web application type.
 * Authority/posture guards intentionally mirror the Servlet management configuration
 * ({@code ACTUATOR} + {@code LOCAL}); do not extract a shared meta-annotation.
 * <p>
 * Absolute path composition uses {@code management.endpoints.web.base-path} with the
 * same normalisation rules as the Servlet management configuration.
 */
@ManagementContextConfiguration
@ConditionalOnClass(DispatcherHandler.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(
        name = "platform.tracing.configuration.authority",
        havingValue = "ACTUATOR",
        matchIfMissing = false)
@ConditionalOnProperty(
        name = "platform.environment",
        havingValue = "LOCAL",
        matchIfMissing = false)
public class TracingControlWebFluxManagementContextConfiguration {

    @Value("${management.endpoints.web.base-path:/actuator}")
    private String managementBasePath;

    @Bean
    public RouterFunction<ServerResponse> tracingControlWebFluxRoutes(
            TracingPolicyMutationService adapter) {
        String base = normalise(managementBasePath) + "/tracingControl";
        return RouterFunctions.route()
                .POST(base + "/sampling/enabled",
                        req -> bindApply(req, SetSamplingEnabledRequest.class,
                                body -> adapter.setSamplingEnabled(body.enabled()),
                                "sampling.enabled",
                                SetSamplingEnabledRequest::enabled))
                .POST(base + "/sampling/ratio",
                        req -> bindApply(req, SetSamplingRatioRequest.class,
                                body -> adapter.setSamplingRatio(body.ratio()),
                                "sampling.ratio",
                                SetSamplingRatioRequest::ratio))
                .PUT(base + "/sampling/route-ratios",
                        req -> bindApply(req, ReplaceSamplingRouteRatiosRequest.class,
                                body -> adapter.replaceSamplingRouteRatios(body.routeRatios()),
                                "sampling.route-ratios",
                                body -> body.routeRatios() != null ? body.routeRatios().size() : 0))
                .POST(base + "/validation/enabled",
                        req -> bindApply(req, SetValidationEnabledRequest.class,
                                body -> adapter.setValidationEnabled(body.enabled()),
                                "validation.enabled",
                                SetValidationEnabledRequest::enabled))
                .POST(base + "/validation/strict",
                        req -> bindApply(req, SetValidationStrictRequest.class,
                                body -> adapter.setValidationStrict(body.strict()),
                                "validation.strict",
                                SetValidationStrictRequest::strict))
                .POST(base + "/exporter/enabled",
                        req -> bindApply(req, SetExporterEnabledRequest.class,
                                body -> adapter.setExporterEnabled(body.enabled()),
                                "exporter.enabled",
                                SetExporterEnabledRequest::enabled))
                .POST(base + "/propagation/trace-control-headers-enabled",
                        req -> bindApply(req, SetTraceControlHeadersEnabledRequest.class,
                                body -> adapter.setTraceControlHeadersEnabled(body.enabled()),
                                "propagation.trace-control-headers.enabled",
                                SetTraceControlHeadersEnabledRequest::enabled))
                .POST(base + "/diagnostics/log-level",
                        req -> bindApply(req, SetDiagnosticsLogLevelRequest.class,
                                body -> adapter.setDiagnosticsLogLevel(body.level()),
                                "diagnostics.log-level",
                                body -> body.level() != null ? body.level() : "null"))
                .build();
    }

    private static <T> Mono<ServerResponse> bindApply(
            ServerRequest request,
            Class<T> bodyType,
            Function<T, TracingPolicyDeliveryResult> apply,
            String leaf,
            Function<T, Object> value) {
        return request.bodyToMono(bodyType)
                .flatMap(body -> Mono
                        .fromCallable(() -> apply.apply(body))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(result -> outcome(result, leaf, value.apply(body))));
    }

    private static Mono<ServerResponse> outcome(
            TracingPolicyDeliveryResult result, String leaf, Object value) {
        int status = switch (result) {
            case APPLIED, UNCHANGED -> 200;
            case REJECTED -> 422;
            case STALE -> 409;
            case FAILED -> 503;
        };
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "outcome", result.name(),
                        "leaf", leaf,
                        "value", value));
    }

    private static String normalise(String raw) {
        String b = raw == null ? "/actuator" : raw.trim();
        if (!b.startsWith("/")) {
            b = "/" + b;
        }
        if (b.endsWith("/") && b.length() > 1) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }
}
