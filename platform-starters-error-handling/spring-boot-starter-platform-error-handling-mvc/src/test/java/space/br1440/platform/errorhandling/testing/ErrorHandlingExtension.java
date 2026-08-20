package space.br1440.platform.errorhandling.testing;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.MDC;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.advice.GlobalExceptionHandler;
import space.br1440.platform.errorhandling.metrics.NoopExceptionMetricRecorder;
import space.br1440.platform.errorhandling.mvc.internal.ExceptionLogger;
import space.br1440.platform.errorhandling.mvc.internal.ProblemResponseFactory;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.testing.support.TestUnknownExceptionHandler;
import space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/**
 * JUnit 5 Extension для автоматического создания {@link GlobalExceptionHandler}
 * из параметров аннотации {@link ErrorHandlingTest}.
 *
 * <p>Поддерживает:
 * <ul>
 *   <li>Инъекцию handler в поля, аннотированные {@link InjectHandler}</li>
 *   <li>Инъекцию handler как параметра тестового метода</li>
 *   <li>Автоматическую очистку MDC после каждого теста</li>
 * </ul>
 *
 * <p>Поиск аннотации {@link ErrorHandlingTest} выполняется вверх по цепочке
 * {@code getEnclosingClass()} для корректной работы с {@code @Nested}-классами.
 */
public class ErrorHandlingExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(ErrorHandlingExtension.class);
    private static final String HANDLER_KEY = "handler";

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        MDC.clear();

        ErrorHandlingTest annotation = findAnnotation(context);
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(annotation.loggingStrategy());

        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                properties,
                Collections.emptyList(),
                new TestUnknownExceptionHandler(),
                new NoopExceptionMetricRecorder(),
                new ProblemResponseFactory(new ErrorEntryToDtoMapper(), annotation.applicationName(), properties),
                new ExceptionLogger(properties),
                space.br1440.platform.errorhandling.testing.support.TestCorrelationIdResolver.defaultInstance(),
                Collections.emptyList(),
                new StackHintExtractor(false, List.of()),
                Collections.emptyList(),
                null
        );

        context.getStore(NAMESPACE).put(HANDLER_KEY, handler);
        injectHandlerFields(context, handler);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        MDC.clear();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == GlobalExceptionHandler.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE).get(HANDLER_KEY, GlobalExceptionHandler.class);
    }

    private ErrorHandlingTest findAnnotation(ExtensionContext context) {
        Class<?> current = context.getRequiredTestClass();
        while (current != null) {
            ErrorHandlingTest ann = current.getAnnotation(ErrorHandlingTest.class);
            if (ann != null) {
                return ann;
            }

            current = current.getEnclosingClass();
        }

        throw new ExtensionConfigurationException(
                "@ErrorHandlingTest not found on " + context.getRequiredTestClass().getName()
                        + " or any enclosing class");
    }

    private void injectHandlerFields(ExtensionContext context, GlobalExceptionHandler handler) throws Exception {
        Object testInstance = context.getRequiredTestInstance();
        for (Class<?> cls = testInstance.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (field.isAnnotationPresent(InjectHandler.class)
                        && field.getType() == GlobalExceptionHandler.class) {
                    field.setAccessible(true);
                    field.set(testInstance, handler);
                }
            }
        }

        context.getTestInstances().ifPresent(instances -> {
            for (Object instance : instances.getAllInstances()) {
                if (instance == testInstance) {
                    continue;
                }

                for (Field field : instance.getClass().getDeclaredFields()) {
                    if (field.isAnnotationPresent(InjectHandler.class)
                            && field.getType() == GlobalExceptionHandler.class) {
                        field.setAccessible(true);
                        try {
                            field.set(instance, handler);
                        } catch (IllegalAccessException e) {
                            throw new ExtensionConfigurationException("Cannot inject handler into field " + field.getName(), e);
                        }
                    }
                }
            }
        });
    }
}
