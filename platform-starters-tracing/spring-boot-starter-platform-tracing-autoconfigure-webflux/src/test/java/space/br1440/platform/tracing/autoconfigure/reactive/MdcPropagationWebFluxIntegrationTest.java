package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.TracingRuntimeDiagnosticsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration;

import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

@SpringBootTest(
        classes = MdcPropagationWebFluxIntegrationTest.ReactiveTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.main.web-application-type=reactive",
                "platform.tracing.enabled=false",
                "platform.tracing.control.channel=NONE"
        })
@ContextConfiguration(initializers = MdcPropagationWebFluxIntegrationTest.DisableAutomaticContextPropagation.class)
class MdcPropagationWebFluxIntegrationTest {

    private static final String BOOT_REACTOR_FILTER = "reactorAutoConfigurationLazyInitializationExcludeFilter";
    private static final String PROBE_KEY = "mdc-propagation-probe";
    private static final ThreadLocal<String> PROBE = new ThreadLocal<>();

    @Autowired
    private ConfigurableApplicationContext context;

    @BeforeEach
    void registerProbeAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(PROBE_KEY, PROBE::get, PROBE::set, PROBE::remove);
    }

    @AfterEach
    void removeProbeAccessor() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(PROBE_KEY);
        PROBE.remove();
    }

    @AfterAll
    static void releaseAutomaticContextPropagation() {
        Hooks.disableAutomaticContextPropagation();
    }

    @Test
    @DisplayName("конфигурация eager-init зарегистрирована")
    void eagerInitConfigurationIsRegistered() {
        assertThat(context.getEnvironment().getProperty("spring.main.lazy-initialization", Boolean.class, false))
                .as("outer context keeps Boot's default lazy-initialization")
                .isFalse();

        assertThat(context.getBeanFactory().getBeanNamesForType(TracingReactorEagerInitConfiguration.class, false, false))
                .as("TracingReactorEagerInitConfiguration must be registered in a reactive application")
                .hasSize(1);
    }

    @Test
    @DisplayName("ThreadLocal переживает смену потока внутри реактивной цепочки")
    void threadLocalIsRestoredAcrossThreadHop() {
        assertThreadLocalRestoredAcrossThreadHop();
    }

    @Nested
    @TestPropertySource(properties = "spring.main.lazy-initialization=true")
    @DisplayName("при lazy-initialization=true")
    class LazyInitialization {

        @Autowired
        private ConfigurableApplicationContext context;

        @Test
        @DisplayName("Boot исключает ReactorAutoConfiguration из lazy-init")
        void bootFilterExcludesReactorAutoConfiguration() {
            assertThat(this.context.getEnvironment().getProperty("spring.main.lazy-initialization", Boolean.class))
                    .as("nested context must actually run with lazy-initialization=true")
                    .isTrue();

            ConfigurableListableBeanFactory beanFactory = this.context.getBeanFactory();
            String reactorBeanName = reactorAutoConfigurationBeanName(beanFactory);
            assertThat(beanFactory.containsSingleton(reactorBeanName))
                    .as("ReactorAutoConfiguration must already be instantiated; the check must not create it")
                    .isTrue();

            assertThat(beanFactory.containsBeanDefinition(BOOT_REACTOR_FILTER)).isTrue();
            LazyInitializationExcludeFilter filter = this.context.getBean(
                    BOOT_REACTOR_FILTER,
                    LazyInitializationExcludeFilter.class
            );
            BeanDefinition definition = beanFactory.getBeanDefinition(reactorBeanName);
            assertThat(filter.isExcluded(reactorBeanName, definition, ReactorAutoConfiguration.class))
                    .isTrue();
            assertThat(beanFactory.containsBeanDefinition("platformTracingEagerReactorAutoConfigurationFilter"))
                    .isFalse();
        }

        @Test
        @DisplayName("ThreadLocal переживает смену потока")
        void threadLocalIsRestoredAcrossThreadHop() {
            assertThreadLocalRestoredAcrossThreadHop();
        }
    }

    private static void assertThreadLocalRestoredAcrossThreadHop() {
        String observed = Mono.fromCallable(PROBE::get)
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(Context.of(PROBE_KEY, "expected"))
                .block(Duration.ofSeconds(5));

        assertThat(observed)
                .as("Hooks.enableAutomaticContextPropagation must be active")
                .isEqualTo("expected");
    }

    private static String reactorAutoConfigurationBeanName(ConfigurableListableBeanFactory beanFactory) {
        String[] names = beanFactory.getBeanNamesForType(ReactorAutoConfiguration.class, false, false);
        assertThat(names)
                .as("ReactorAutoConfiguration bean name must be resolvable without initializing it")
                .hasSize(1);
        return names[0];
    }

    static final class DisableAutomaticContextPropagation implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(@Nonnull ConfigurableApplicationContext applicationContext) {
            Hooks.disableAutomaticContextPropagation();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            TracingRuntimeAutoConfiguration.class,
            TracingRuntimeDiagnosticsAutoConfiguration.class,
            TracingStatusAutoConfiguration.class
    })
    static class ReactiveTestApp {
    }
}
