package space.br1440.platform.errorhandling.testing;

import org.junit.jupiter.api.extension.*;
import org.junit.platform.commons.support.AnnotationSupport;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.testing.annotation.ExpectErrorEntry;
import space.br1440.platform.errorhandling.testing.annotation.WithCauseChain;
import space.br1440.platform.errorhandling.testing.annotation.WithStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 Extension для тестирования стратегий обработки ошибок.
 *
 * <p>Паттерн: {@code BeforeEachCallback + AfterEachCallback + ParameterResolver}
 * (аналогично {@code MockitoExtension}, {@code SpringExtension}).
 *
 * <p>Основные возможности:
 * <ul>
 *   <li>Сканирование {@code @WithStrategy} → инстанцирование стратегий</li>
 *   <li>Построение {@code CauseChainResolver} из зарегистрированных стратегий</li>
 *   <li>Создание {@code ErrorHandlingContext} с default properties</li>
 *   <li>Построение cause chain из {@code @WithCauseChain}</li>
 *   <li>Авто-верификация результата через {@code @ExpectErrorEntry}</li>
 *   <li>Injection {@code StrategyTestContext} и его компонентов через параметры</li>
 * </ul>
 */
public class ErrorHandlingExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(ErrorHandlingExtension.class);

    private static final String CONTEXT_KEY = "strategyTestContext";

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        List<TopExceptionHandlingStrategy> topStrategies = new ArrayList<>();
        List<RootCauseExceptionHandlingStrategy> rootCauseStrategies = new ArrayList<>();

        List<WithStrategy> annotations = collectWithStrategyAnnotations(extensionContext);
        for (WithStrategy annotation : annotations) {
            Object strategy = instantiateStrategy(annotation);
            if (strategy instanceof TopExceptionHandlingStrategy top) {
                topStrategies.add(top);
            }

            if (strategy instanceof RootCauseExceptionHandlingStrategy rootCause) {
                rootCauseStrategies.add(rootCause);
            }
        }

        CauseChainResolver resolver = new CauseChainResolver(rootCauseStrategies);
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        ErrorHandlingContext context = ErrorHandlingContext.of(
                properties, "/test", new StackHintExtractor(false, List.of()));

        StrategyTestContext testContext = new StrategyTestContext(
                topStrategies, rootCauseStrategies, resolver, context, properties
        );

        AnnotationSupport.findAnnotation(extensionContext.getRequiredTestMethod(), WithCauseChain.class)
                .ifPresent(chain -> {
                    Throwable built = ExceptionChainBuilder.fromAnnotation(
                            extensionContext.getRequiredTestClass(),
                            chain.exceptions(),
                            chain.factories()
                    );
                    testContext.setBuiltCauseChain(built);
                });

        extensionContext.getStore(NAMESPACE).put(CONTEXT_KEY, testContext);
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        if (extensionContext.getExecutionException().isPresent()) {
            return;
        }

        AnnotationSupport.findAnnotation(
                extensionContext.getRequiredTestMethod(), ExpectErrorEntry.class
        ).ifPresent(expected -> {
            StrategyTestContext ctx = getTestContext(extensionContext);
            ErrorEntry result = ctx.getLastResult();

            assertThat(result)
                    .as("@ExpectErrorEntry: lastResult не должен быть null — " +
                            "вызовите ctx.handleWith() в теле теста")
                    .isNotNull();

            assertThat(result.getHttpStatus().value())
                    .as("HTTP status")
                    .isEqualTo(expected.status());

            if (!expected.errorCode().isEmpty()) {
                assertThat(result.getErrorCode())
                        .as("errorCode")
                        .isEqualTo(expected.errorCode());
            }

            if (!expected.reason().isEmpty()) {
                assertThat(result.getReason())
                        .as("reason")
                        .isEqualTo(expected.reason());
            }
        });
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type.equals(StrategyTestContext.class)
                || type.equals(CauseChainResolver.class)
                || type.equals(ErrorHandlingContext.class)
                || type.equals(ErrorHandlingProperties.class)
                || TopExceptionHandlingStrategy.class.isAssignableFrom(type)
                || RootCauseExceptionHandlingStrategy.class.isAssignableFrom(type);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {
        StrategyTestContext ctx = getTestContext(extensionContext);
        Class<?> type = parameterContext.getParameter().getType();

        if (type.equals(StrategyTestContext.class)) return ctx;
        if (type.equals(CauseChainResolver.class)) return ctx.getResolver();
        if (type.equals(ErrorHandlingContext.class)) return ctx.getContext();
        if (type.equals(ErrorHandlingProperties.class)) return ctx.getProperties();

        if (TopExceptionHandlingStrategy.class.isAssignableFrom(type)) {
            return ctx.getTopStrategies().stream()
                    .filter(type::isInstance)
                    .findFirst()
                    .orElseThrow(() -> new ParameterResolutionException(
                            "Стратегия " + type.getSimpleName() + " не зарегистрирована через @WithStrategy"));
        }

        if (RootCauseExceptionHandlingStrategy.class.isAssignableFrom(type)) {
            return ctx.getRootCauseStrategies().stream()
                    .filter(type::isInstance)
                    .findFirst()
                    .orElseThrow(() -> new ParameterResolutionException(
                            "Стратегия " + type.getSimpleName() + " не зарегистрирована через @WithStrategy"));
        }

        throw new ParameterResolutionException("Unsupported parameter type: " + type.getName());
    }

    private StrategyTestContext getTestContext(ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE)
                .get(CONTEXT_KEY, StrategyTestContext.class);
    }

    /**
     * Собирает {@code @WithStrategy} с метода и класса (включая {@code @Nested}).
     * Обход вверх по иерархии контекстов (паттерн WireMockExtension).
     */
    private List<WithStrategy> collectWithStrategyAnnotations(ExtensionContext context) {

        List<WithStrategy> result = new ArrayList<>(
                AnnotationSupport.findRepeatableAnnotations(context.getRequiredTestMethod(), WithStrategy.class)
        );

        Optional<ExtensionContext> current = Optional.of(context);
        while (current.isPresent()) {
            current.flatMap(ExtensionContext::getTestClass)
                    .ifPresent(cls -> result.addAll(AnnotationSupport.findRepeatableAnnotations(cls, WithStrategy.class))
            );
            current = current.flatMap(ExtensionContext::getParent);
        }

        return result;
    }

    /**
     * Инстанцирует стратегию, пробуя конструкторы в порядке:
     * {@code ()}, {@code (CauseChainResolver)}, {@code (boolean)},
     * {@code (StackHintExtractor)}, {@code (boolean, StackHintExtractor)}.
     */
    private Object instantiateStrategy(WithStrategy annotation) {
        Class<?> strategyClass = annotation.value();
        StackHintExtractor defaultExtractor = new StackHintExtractor(false, List.of());
        try {
            Constructor<?>[] constructors = strategyClass.getDeclaredConstructors();
            Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));

            for (Constructor<?> ctor : constructors) {
                ctor.setAccessible(true);
                Parameter[] params = ctor.getParameters();

                if (params.length == 0) {
                    return ctor.newInstance();
                }

                if (params.length == 1 && params[0].getType() == boolean.class) {
                    return ctor.newInstance(true);
                }

                if (params.length == 1 && params[0].getType() == CauseChainResolver.class) {
                    return ctor.newInstance(new CauseChainResolver(List.of()));
                }

                if (params.length == 1 && params[0].getType() == StackHintExtractor.class) {
                    return ctor.newInstance(defaultExtractor);
                }

                if (params.length == 2
                        && params[0].getType() == boolean.class
                        && params[1].getType() == StackHintExtractor.class) {
                    return ctor.newInstance(true, defaultExtractor);
                }
            }

            return strategyClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ExtensionConfigurationException(
                    "Не удалось инстанцировать стратегию " + strategyClass.getName() +
                            ". Убедитесь, что класс доступен и имеет совместимый конструктор.", e);
        }
    }
}
