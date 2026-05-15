package space.br1440.platform.errorhandling.autoconfigure.serialization;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.serialization.YamlExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.yaml.snakeyaml.error.YAMLException")
public class ErrorHandlingYamlConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "yamlExceptionStrategy")
    public RootCauseExceptionHandlingStrategy yamlExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new YamlExceptionStrategy(stackHintExtractor);
    }
}