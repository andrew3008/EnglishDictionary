package space.br1440.platform.errorhandling.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;

class PlatformConfigurationFailureAnalyzerTest {

    private final PlatformConfigurationFailureAnalyzer analyzer = new PlatformConfigurationFailureAnalyzer();

    @Test
    void rendersDescriptionAndActionFromExceptionFields() {
        // given
        PlatformConfigurationException exception = new PlatformConfigurationException(
                "Extension version 2.1.0 is incompatible with starter version 2.0.0",
                "Align the starter and controlled-agent distribution to the same release."
        );

        // when
        FailureAnalysis analysis = analyzer.analyze(exception);

        // then
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo(exception.getMessage());
        assertThat(analysis.getAction()).isEqualTo(exception.getAction());
    }

    @Test
    void exposesTheOriginalExceptionAsCause() {
        // given
        Throwable rootCause = new IllegalStateException("resource not found");
        PlatformConfigurationException exception = new PlatformConfigurationException(
                "Unable to read module-version.properties",
                "Reinstall the starter",
                rootCause
        );

        // when
        FailureAnalysis analysis = analyzer.analyze(exception);

        // then
        assertThat(analysis.getCause()).isSameAs(exception);
    }

    @Test
    void findsWrappedExceptionInsideTheCauseChain() {
        // given
        PlatformConfigurationException configException = new PlatformConfigurationException("msg", "action");
        RuntimeException wrapper = new RuntimeException("context refresh failed", configException);

        // when
        FailureAnalysis analysis = analyzer.analyze(wrapper);

        // then
        assertThat(analysis).isNotNull();
        assertThat(analysis.getCause()).isSameAs(configException);
    }

    @Test
    void returnsNullWhenNoPlatformConfigurationExceptionIsInTheCauseChain() {
        // given
        RuntimeException unrelated = new RuntimeException("unrelated failure");

        // when
        FailureAnalysis analysis = analyzer.analyze(unrelated);

        // then
        assertThat(analysis).isNull();
    }

    @Test
    void isRegisteredAsASpringBootFailureAnalyzer() {
        // given
        List<Throwable> instantiationFailures = new ArrayList<>();
        ArgumentResolver arguments = ArgumentResolver.of(BeanFactory.class, new DefaultListableBeanFactory())
                .and(Environment.class, new StandardEnvironment());

        // when
        List<FailureAnalyzer> analyzers = SpringFactoriesLoader
                .forDefaultResourceLocation(PlatformConfigurationFailureAnalyzer.class.getClassLoader())
                .load(FailureAnalyzer.class, arguments,
                        FailureHandler.handleMessage((message, failure) -> instantiationFailures.add(failure)));

        // then
        assertThat(analyzers)
                .as("instantiation failures: %s", instantiationFailures)
                .hasAtLeastOneElementOfType(PlatformConfigurationFailureAnalyzer.class);
    }
}
