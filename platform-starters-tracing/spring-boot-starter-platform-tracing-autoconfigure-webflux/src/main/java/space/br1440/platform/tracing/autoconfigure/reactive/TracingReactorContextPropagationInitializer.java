package space.br1440.platform.tracing.autoconfigure.reactive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

import reactor.core.publisher.Hooks;

@Slf4j
final class TracingReactorContextPropagationInitializer implements SmartInitializingSingleton {

    private static final String PROPERTY_NAME = "spring.reactor.context-propagation";
    private static final String AUTO_MODE = "AUTO";

    private final Environment environment;

    TracingReactorContextPropagationInitializer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String mode = environment.getProperty(PROPERTY_NAME);
        if ((mode == null) || AUTO_MODE.equalsIgnoreCase(mode)) {
            Hooks.enableAutomaticContextPropagation();
            return;
        }

        log.warn("{}={} — platform does not override this explicit application choice. Automatic "
                + "identity/MDC propagation across Reactor scheduler switches is not guaranteed: "
                + "requestId, correlationId and MDC keys may be absent after publishOn/subscribeOn/retry "
                + "hops on WebFlux (see TracingReactorEagerInitConfiguration).", PROPERTY_NAME, mode);
    }
}
