package space.br1440.platform.metrics.configuration.processor;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformMetricsEnvironmentPostProcessorTest {

    @Mock
    private DeferredLogFactory logFactory;

    @Test
    void orderIsLowestPrecedence() {
        when(logFactory.getLog(any(Class.class))).thenReturn(mock(Log.class));
        PlatformMetricsEnvironmentPostProcessor epp = new PlatformMetricsEnvironmentPostProcessor(logFactory);
        assertThat(epp.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void registersDefaultPropertySourceAtEndWhenClasspathResourcePresent() {
        when(logFactory.getLog(any(Class.class))).thenReturn(mock(Log.class));

        PlatformMetricsEnvironmentPostProcessor epp = new PlatformMetricsEnvironmentPostProcessor(logFactory);
        StandardEnvironment environment = new StandardEnvironment();

        epp.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().contains("platform.metrics.defaults")).isTrue();
        assertThat(environment.getProperty("platform.metrics.http.timer-metric-name"))
                .isEqualTo("platform.http.server.requests");
    }
}
