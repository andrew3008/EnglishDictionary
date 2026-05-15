package space.br1440.platform.errorhandling.webflux.testing;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.MDC;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;
import space.br1440.platform.errorhandling.webflux.testing.support.TestUnknownExceptionHandler;

import java.lang.reflect.Field;

public class ReactiveErrorHandlingExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(ReactiveErrorHandlingExtension.class);
    private static final String HANDLER_KEY = "handler";

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        MDC.clear();

        ReactiveErrorHandlingTest annotation = findAnnotation(context);
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(annotation.loggingStrategy());

        ReactiveGlobalExceptionHandler handler = ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .unknownHandler(new TestUnknownExceptionHandler())
                .applicationName(annotation.applicationName())
                .build();

        context.getStore(NAMESPACE).put(HANDLER_KEY, handler);
        injectHandler(context, handler);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        MDC.clear();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == ReactiveGlobalExceptionHandler.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE).get(HANDLER_KEY, ReactiveGlobalExceptionHandler.class);
    }

    private ReactiveErrorHandlingTest findAnnotation(ExtensionContext context) {
        Class<?> current = context.getRequiredTestClass();
        while (current != null) {
            ReactiveErrorHandlingTest ann = current.getAnnotation(ReactiveErrorHandlingTest.class);
            if (ann != null) {
                return ann;
            }

            current = current.getEnclosingClass();
        }

        throw new ExtensionConfigurationException(
                "@ReactiveErrorHandlingTest not found on " + context.getRequiredTestClass().getName()
                        + " or any enclosing class");
    }

    private void injectHandler(ExtensionContext context, ReactiveGlobalExceptionHandler handler) throws Exception {
        Object testInstance = context.getRequiredTestInstance();
        injectFields(testInstance, handler);

        context.getTestInstances().ifPresent(instances -> {
            for (Object instance : instances.getAllInstances()) {
                if (instance == testInstance) {
                    continue;
                }

                try {
                    injectFields(instance, handler);
                } catch (Exception e) {
                    throw new ExtensionConfigurationException(
                            "Cannot inject handler into field on " + instance.getClass().getName(), e);
                }
            }
        });
    }

    private static void injectFields(Object instance, ReactiveGlobalExceptionHandler handler) throws Exception {
        for (Class<?> cls = instance.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (field.isAnnotationPresent(InjectHandler.class)
                        && field.getType() == ReactiveGlobalExceptionHandler.class) {
                    field.setAccessible(true);
                    field.set(instance, handler);
                }
            }
        }
    }
}
