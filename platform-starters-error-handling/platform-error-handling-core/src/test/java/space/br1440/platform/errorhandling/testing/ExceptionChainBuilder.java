package space.br1440.platform.errorhandling.testing;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Fluent-builder для декларативного построения цепочек причин (cause chain).
 *
 * <p>Поддерживает как простые типы с {@code (String)} / {@code (String, Throwable)}
 * конструкторами, так и произвольные фабрики для сложных типов без no-arg конструктора
 * ({@code PSQLException}, {@code MongoCommandException}).
 *
 * <pre>{@code
 * Throwable chain = ExceptionChainBuilder.root(new TimeoutException("timed out"))
 *     .wrappedBy(msg -> new ExecutionException(msg, null))
 *     .wrappedBy("KafkaException wrapper", RuntimeException::new)
 *     .build();
 * }</pre>
 */
public final class ExceptionChainBuilder {

    private final List<ThrowableFactory> layers = new ArrayList<>();

    private ExceptionChainBuilder(Throwable root) {
        this.layers.add(cause -> root);
    }

    /**
     * Начинает цепочку с готового root-cause.
     */
    public static ExceptionChainBuilder root(Throwable rootCause) {
        return new ExceptionChainBuilder(rootCause);
    }

    /**
     * Начинает цепочку с типа и сообщения.
     * Пробует конструкторы {@code (String, Throwable)}, {@code (String)}, {@code ()}.
     */
    public static ExceptionChainBuilder root(Class<? extends Throwable> type, String message) {
        return new ExceptionChainBuilder(instantiate(type, message, null));
    }

    /**
     * Оборачивает текущую цепочку в исключение, созданное фабрикой.
     * Фабрика получает предыдущий элемент как cause.
     */
    public ExceptionChainBuilder wrappedBy(ThrowableFactory factory) {
        layers.add(factory);
        return this;
    }

    /**
     * Оборачивает текущую цепочку в исключение указанного типа с сообщением.
     */
    public ExceptionChainBuilder wrappedBy(Class<? extends Throwable> type, String message) {
        layers.add(cause -> instantiate(type, message, cause));
        return this;
    }

    /**
     * Оборачивает текущую цепочку через {@link Supplier} (cause игнорируется).
     * Подходит для типов, которые принимают cause через initCause().
     */
    public ExceptionChainBuilder wrappedBy(Supplier<? extends Throwable> supplier) {
        layers.add(cause -> {
            Throwable wrapper = supplier.get();
            try {
                wrapper.initCause(cause);
            } catch (IllegalStateException ignored) {
                // cause уже был инициализирован в конструкторе
            }
            return wrapper;
        });

        return this;
    }

    /**
     * Собирает цепочку. Возвращает самый внешний (top-level) элемент.
     */
    public Throwable build() {
        Throwable current = null;
        for (ThrowableFactory factory : layers) {
            current = factory.create(current);
        }

        return current;
    }

    /**
     * Создаёт цепочку из аннотации {@code @WithCauseChain}: массивов классов и фабрик.
     *
     * @param testClass  класс теста (для поиска фабричных методов)
     * @param exceptions классы исключений
     * @param factories  имена статических методов или пустые строки
     */
    public static Throwable fromAnnotation(Class<?> testClass,
                                           Class<? extends Throwable>[] exceptions,
                                           String[] factories) {
        Throwable current = null;

        for (int i = exceptions.length - 1; i >= 0; i--) {
            String factory = (factories.length > i) ? factories[i] : "";
            if (factory != null && !factory.isEmpty()) {
                current = invokeFactory(testClass, factory, current);
            } else {
                current = instantiate(exceptions[i], "test-" + exceptions[i].getSimpleName(), current);
            }
        }

        return current;
    }

    private static Throwable invokeFactory(Class<?> testClass, String methodName, Throwable cause) {
        try {
            try {
                Method m = testClass.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return (Throwable) m.invoke(null);
            } catch (NoSuchMethodException e) {
                Method m = testClass.getDeclaredMethod(methodName, Throwable.class);
                m.setAccessible(true);
                return (Throwable) m.invoke(null, cause);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke factory method '" + methodName + "' on " + testClass.getName(), e);
        }
    }

    private static Throwable instantiate(Class<? extends Throwable> type, String message, Throwable cause) {
        // (String, Throwable) — предпочтительный
        try {
            Constructor<? extends Throwable> ctor = type.getDeclaredConstructor(String.class, Throwable.class);
            ctor.setAccessible(true);
            return ctor.newInstance(message, cause);
        } catch (NoSuchMethodException ignored) { /* fallthrough */ } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate " + type.getName(), e);
        }

        // (String) — без cause, назначим через initCause
        try {
            Constructor<? extends Throwable> ctor = type.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            Throwable t = ctor.newInstance(message);
            if (cause != null) {
                try { t.initCause(cause); } catch (IllegalStateException ignored) { }
            }
            return t;
        } catch (NoSuchMethodException ignored) { /* fallthrough */ } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate " + type.getName(), e);
        }

        // () — no-arg
        try {
            Constructor<? extends Throwable> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            Throwable t = ctor.newInstance();
            if (cause != null) {
                try { t.initCause(cause); } catch (IllegalStateException ignored) { }
            }
            return t;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "No suitable constructor found for " + type.getName()
                            + ". Use @WithCauseChain(factories = {...}) for types without (String) constructor.", e);
        }
    }

    /**
     * Создаёт исключение, чей {@link Throwable#getCause()} возвращает {@code cyclicCause},
     * симулируя циклическую цепочку причин без рефлексивного доступа к
     * {@code java.lang.Throwable.cause} (запрещён в Java 17+ модульной системой).
     *
     * <p>Использование:
     * <pre>{@code
     * Exception a = new Exception("a");
     * Exception b = new Exception("b", a);
     * Exception aWithCycle = ExceptionChainBuilder.withForcedCause(a, b);
     * // aWithCycle.getCause() == b → b.getCause() == aWithCycle: цикл
     * }</pre>
     *
     * @param message    сообщение нового исключения (то же, что у оригинального {@code target})
     * @param cyclicCause исключение, которое будет возвращаться из {@link Throwable#getCause()}
     * @return новое исключение с переопределённым {@code getCause()}
     */
    public static Exception withForcedCause(String message, Throwable cyclicCause) {
        return new Exception(message) {
            @Override
            public synchronized Throwable getCause() {
                return cyclicCause;
            }
        };
    }

    @FunctionalInterface
    public interface ThrowableFactory {
        Throwable create(Throwable cause);
    }

}
