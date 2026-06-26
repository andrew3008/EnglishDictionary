package space.br1440.platform.errorhandling.webflux.testing.handler;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public final class ReactiveGlobalExceptionHandlerExtension implements BeforeEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(ReactiveGlobalExceptionHandlerExtension.class);
    private static final String STORE_KEY = "reactiveGlobalExceptionHandler";

    private static final String FALLBACK_APPLICATION_NAME = "test-app";

    @Override
    public void beforeEach(@NonNull ExtensionContext context) {
        Method testMethod = context.getRequiredTestMethod();
        if (methodDoesNotRequestHandler(testMethod)) {
            return;
        }

        ReactiveHandler methodAnnotation = testMethod.getAnnotation(ReactiveHandler.class);
        if (methodAnnotation == null) {
            throw new ExtensionConfigurationException("Параметр ReactiveGlobalExceptionHandler требует @ReactiveHandler на @Test методе.");
        }

        ExceptionMetricRecorder recorder = newInstanceRecorder(methodAnnotation.recorder());
        ErrorHandlingProperties properties = resolveProperties(methodAnnotation);

        validateStrategySpecs(methodAnnotation);
        List<TopExceptionHandlingStrategy> strategies = resolveStrategies(methodAnnotation);

        ReactiveHandlerTest classAnn = findReactiveHandlerTest(context.getRequiredTestClass());
        String applicationName = resolveApplicationName(methodAnnotation, classAnn);

        ReactiveGlobalExceptionHandler handler = ReactiveGlobalExceptionHandlerTestFixture.build(
                properties,
                strategies,
                recorder,
                applicationName);

        context.getStore(NS).put(STORE_KEY, handler);
    }

    @Override
    public boolean supportsParameter(@NonNull ParameterContext parameterContext,
                                     @NonNull ExtensionContext extensionContext) throws ParameterResolutionException {
        Parameter parameter = parameterContext.getParameter();
        if (parameter.getAnnotation(ReactiveHandler.class) != null) {
            throw new ExtensionConfigurationException("@ReactiveHandler не допускается на параметрах — только на @Test методе.");
        }

        return parameter.getType().equals(ReactiveGlobalExceptionHandler.class);
    }

    @Override
    public Object resolveParameter(@NonNull ParameterContext parameterContext,
                                   @NonNull ExtensionContext extensionContext) throws ParameterResolutionException {
        if (methodDoesNotRequestHandler(extensionContext.getRequiredTestMethod())) {
            throw new ParameterResolutionException(
                    "ReactiveGlobalExceptionHandler не ожидается для этого метода.");
        }

        ReactiveGlobalExceptionHandler handler = extensionContext.getStore(NS)
                .get(STORE_KEY, ReactiveGlobalExceptionHandler.class);

        if (handler == null) {
            throw new ParameterResolutionException("Handler не собран — проверьте @ReactiveHandler на @Test и порядок extension.");
        }

        return handler;
    }

    private static boolean methodDoesNotRequestHandler(@NonNull Method method) {
        return Arrays.stream(method.getParameters())
                .noneMatch(p -> p.getType().equals(ReactiveGlobalExceptionHandler.class));
    }

    private static ReactiveHandlerTest findReactiveHandlerTest(Class<?> clazz) {
        Class<?> cur = clazz;
        while (cur != null) {
            ReactiveHandlerTest ann = cur.getAnnotation(ReactiveHandlerTest.class);
            if (ann != null) {
                return ann;
            }

            cur = cur.getEnclosingClass();
        }

        return null;
    }

    private static void validateStrategySpecs(ReactiveHandler methodAnn) {
        Class<?>[] strategies = methodAnn.strategies();
        Class<? extends Supplier<List<TopExceptionHandlingStrategy>>> factoryCls = methodAnn.strategyFactory();

        boolean hasStrategies = strategies.length > 0;
        boolean hasFactory = !factoryCls.equals(NoStrategyFactorySupplier.class);

        if (hasStrategies && hasFactory) {
            throw new ExtensionConfigurationException("Задайте либо @ReactiveHandler.strategies, либо strategyFactory, но не оба одновременно.");
        }

        if (!hasStrategies && !hasFactory) {
            throw new ExtensionConfigurationException("Задайте @ReactiveHandler.strategies или strategyFactory со списком стратегий.");
        }
    }

    private static List<TopExceptionHandlingStrategy> resolveStrategies(ReactiveHandler methodAnn) {
        Class<?>[] strategyClasses = methodAnn.strategies();
        if (strategyClasses.length > 0) {
            List<TopExceptionHandlingStrategy> list = new ArrayList<>(strategyClasses.length);
            for (Class<?> strategyClass : strategyClasses) {
                Class<? extends TopExceptionHandlingStrategy> typed = strategyClass.asSubclass(TopExceptionHandlingStrategy.class);
                list.add(instantiateStrategy(typed));
            }

            return list;
        }

        Class<? extends Supplier<List<TopExceptionHandlingStrategy>>> factoryCls = methodAnn.strategyFactory();
        Supplier<List<TopExceptionHandlingStrategy>> supplier = newStrategyListSupplier(factoryCls);

        List<TopExceptionHandlingStrategy> fromFactory = supplier.get();
        if (fromFactory == null || fromFactory.isEmpty()) {
            throw new ExtensionConfigurationException("strategyFactory вернула пустой список: " + factoryCls.getName());
        }

        return List.copyOf(fromFactory);
    }

    private static TopExceptionHandlingStrategy instantiateStrategy(Class<? extends TopExceptionHandlingStrategy> cls) {
        try {
            Constructor<? extends TopExceptionHandlingStrategy> constructor = cls.getConstructor();
            return constructor.newInstance();
        } catch (Exception ex) {
            throw new ExtensionConfigurationException(
                    "Strategy class " + cls.getName()
                            + " must have a public no-arg constructor (или используйте strategyFactory).",
                    ex);
        }
    }

    private static ErrorHandlingProperties resolveProperties(ReactiveHandler methodAnn) {
        Class<? extends Supplier<ErrorHandlingProperties>> factoryCls = methodAnn.propertiesFactory();
        Supplier<ErrorHandlingProperties> supplier = newPropertiesSupplier(factoryCls);

        ErrorHandlingProperties props = supplier.get();
        if (props == null) {
            throw new ExtensionConfigurationException("propertiesFactory вернула null: " + factoryCls.getName());
        }

        return props;
    }

    private static Supplier<List<TopExceptionHandlingStrategy>> newStrategyListSupplier(Class<? extends Supplier<List<TopExceptionHandlingStrategy>>> cls) {
        try {
            Constructor<? extends Supplier<List<TopExceptionHandlingStrategy>>> ctor = cls.getConstructor();
            return ctor.newInstance();
        } catch (Exception ex) {
            throw new ExtensionConfigurationException(
                    "strategyFactory class " + cls.getName() + " must have a public no-arg constructor.",
                    ex);
        }
    }

    private static Supplier<ErrorHandlingProperties> newPropertiesSupplier(Class<? extends Supplier<ErrorHandlingProperties>> cls) {
        try {
            Constructor<? extends Supplier<ErrorHandlingProperties>> ctor = cls.getConstructor();
            return ctor.newInstance();
        } catch (Exception ex) {
            throw new ExtensionConfigurationException(
                    "propertiesFactory class " + cls.getName() + " must have a public no-arg constructor.",
                    ex);
        }
    }

    private static String resolveApplicationName(ReactiveHandler methodAnn, ReactiveHandlerTest classAnn) {
        String methodName = methodAnn.applicationName();
        if (methodName != null && !methodName.isEmpty()) {
            return methodName;
        }

        if (classAnn != null) {
            return classAnn.applicationName();
        }

        return FALLBACK_APPLICATION_NAME;
    }

    private static ExceptionMetricRecorder newInstanceRecorder(Class<? extends ExceptionMetricRecorder> recorderCls) {
        try {
            Constructor<? extends ExceptionMetricRecorder> ctor = recorderCls.getConstructor();
            return ctor.newInstance();
        } catch (Exception ex) {
            throw new ExtensionConfigurationException(
                    "ExceptionMetricRecorder class " + recorderCls.getName()
                            + " must have a public no-arg constructor.",
                    ex);
        }
    }
}
