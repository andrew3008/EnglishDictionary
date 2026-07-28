package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Computes and exposes the absolute base path for
 * {@link TracingControlMvcController} so that the controller's
 * class-level {@code @RequestMapping} can reference it via a
 * bean SpEL expression:
 * <pre>
 *   &#64;RequestMapping("#{@tracingControlMvcControllerPathProvider.basePath}")
 * </pre>
 * Using a dedicated bean avoids instance-field SpEL inside
 * annotation values, which is not a supported Spring MVC contract.
 * <p>
 * The bean SpEL {@code #{@beanName.property}} is evaluated once
 * at context refresh by the MVC handler mapping infrastructure,
 * after all beans are fully constructed — there is no self-reference.
 */
@Component
public final class TracingControlMvcControllerPathProvider {

    private final String basePath;

    public TracingControlMvcControllerPathProvider(
            @Value("${management.endpoints.web.base-path:/actuator}") String rawBasePath) {
        String b = rawBasePath.trim();
        if (!b.startsWith("/")) b = "/" + b;
        if (b.endsWith("/") && b.length() > 1) b = b.substring(0, b.length() - 1);
        this.basePath = b + "/tracingControl";
    }

    /**
     * Returns the absolute path prefix for all tracingControl routes,
     * e.g. {@code /actuator/tracingControl}.
     */
    public String getBasePath() {
        return basePath;
    }
}
