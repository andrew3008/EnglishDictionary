package space.br1440.platform.errorhandling.webflux.testing;

import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ReactiveErrorHandlingExtension.class)
public @interface ReactiveErrorHandlingTest {

    LoggingStrategy loggingStrategy() default LoggingStrategy.CATEGORY;

    String applicationName() default "test-service";

}
