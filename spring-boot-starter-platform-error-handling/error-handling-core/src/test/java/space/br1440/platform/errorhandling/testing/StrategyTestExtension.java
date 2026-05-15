package space.br1440.platform.errorhandling.testing;

import org.junit.jupiter.api.extension.*;
import org.junit.platform.commons.support.AnnotationSupport;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.testing.annotation.WithStrategy;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link TestTemplateInvocationContextProvider} для матричного тестирования стратегий.
 *
 * <p>Запускает один тестовый метод с каждой стратегией, зарегистрированной через
 * {@code @WithStrategy}. Каждая стратегия получает отдельный invocation context
 * с читаемым именем в отчёте (паттерн из {@code ParameterizedTestExtension}).
 *
 * <p>Пример:
 * <pre>{@code
 * @TestTemplate
 * @ExtendWith(StrategyTestExtension.class)
 * @WithStrategy(TimeoutExceptionStrategy.class)
 * @WithStrategy(SslExceptionStrategy.class)
 * void strategySupportsItsDomain(RootCauseExceptionHandlingStrategy strategy) { ... }
 * }</pre>
 */
public class StrategyTestExtension implements TestTemplateInvocationContextProvider {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(StrategyTestExtension.class);

    private static final String STRATEGIES_KEY = "strategies";

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        List<WithStrategy> annotations = AnnotationSupport.findRepeatableAnnotations(
                context.getRequiredTestMethod(), WithStrategy.class
        );

        if (annotations.isEmpty()) {
            return false;
        }

        context.getStore(NAMESPACE).put(STRATEGIES_KEY, annotations);
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        List<WithStrategy> annotations = (List<WithStrategy>) context.getStore(NAMESPACE)
                .get(STRATEGIES_KEY, List.class);

        return annotations.stream().map(StrategyInvocationContext::new);
    }

    private static class StrategyInvocationContext implements TestTemplateInvocationContext {

        private final WithStrategy annotation;

        StrategyInvocationContext(WithStrategy annotation) {
            this.annotation = annotation;
        }

        @Override
        public String getDisplayName(int invocationIndex) {
            return String.format("[%d] %s", invocationIndex, annotation.value().getSimpleName());
        }

        @Override
        public List<Extension> getAdditionalExtensions() {
            return List.of(new StrategyParameterResolver(annotation));
        }
    }

    private static class StrategyParameterResolver implements ParameterResolver {

        private final WithStrategy annotation;

        StrategyParameterResolver(WithStrategy annotation) {
            this.annotation = annotation;
        }

        @Override
        public boolean supportsParameter(ParameterContext parameterContext,
                                         ExtensionContext extensionContext) {
            Class<?> type = parameterContext.getParameter().getType();
            return type.isAssignableFrom(annotation.value());
        }

        @Override
        public Object resolveParameter(ParameterContext parameterContext,
                                       ExtensionContext extensionContext) {
            Class<?> clazz = annotation.value();
            try {
                Constructor<?> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (NoSuchMethodException e) {
                try {
                    Constructor<?> ctor = clazz.getDeclaredConstructor(StackHintExtractor.class);
                    ctor.setAccessible(true);
                    return ctor.newInstance(new StackHintExtractor(false, List.of()));
                } catch (ReflectiveOperationException e2) {
                    e2.addSuppressed(e);
                    throw new ParameterResolutionException(
                            "Не удалось инстанцировать стратегию " + clazz.getName(), e2);
                }
            } catch (ReflectiveOperationException e) {
                throw new ParameterResolutionException(
                        "Не удалось инстанцировать стратегию " + clazz.getName(), e);
            }
        }
    }

}
