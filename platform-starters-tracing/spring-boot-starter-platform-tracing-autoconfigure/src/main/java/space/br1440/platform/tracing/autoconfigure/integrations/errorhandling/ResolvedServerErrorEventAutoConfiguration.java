package space.br1440.platform.tracing.autoconfigure.integrations.errorhandling;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.autoconfigure.exception.ExceptionRecordingAutoConfiguration;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;

@AutoConfiguration(after = ExceptionRecordingAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "space.br1440.platform.errorhandling.mvc.observability.ExceptionObservabilityHook")
public class ResolvedServerErrorEventAutoConfiguration {

    @Bean
    @ConditionalOnBean(ExceptionRecorder.class)
    @ConditionalOnMissingBean(ResolvedServerErrorEventHook.class)
    ResolvedServerErrorEventHook resolvedServerErrorEventHook(ExceptionRecorder recorder) {
        return new ResolvedServerErrorEventHook(recorder);
    }
}
