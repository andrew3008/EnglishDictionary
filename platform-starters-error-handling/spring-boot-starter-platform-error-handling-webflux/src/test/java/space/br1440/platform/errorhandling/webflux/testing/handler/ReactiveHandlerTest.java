package space.br1440.platform.errorhandling.webflux.testing.handler;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ReactiveGlobalExceptionHandlerExtension.class)
public @interface ReactiveHandlerTest {

    String applicationName() default "test-app";

}
