package space.br1440.platform.errorhandling.webflux.testing.handler;

import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.webflux.testing.support.properties.DefaultPropertiesFactory;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.function.Supplier;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReactiveHandler {

    String applicationName() default "";

    Class<? extends TopExceptionHandlingStrategy>[] strategies() default {};

    Class<? extends Supplier<List<TopExceptionHandlingStrategy>>> strategyFactory() default NoStrategyFactorySupplier.class;

    Class<? extends Supplier<ErrorHandlingProperties>> propertiesFactory() default DefaultPropertiesFactory.class;

    Class<? extends ExceptionMetricRecorder> recorder() default NoopExceptionMetricRecorder.class;

}
