package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

@UtilityClass
public final class ReflectionUtils {

    @FunctionalInterface
    public interface ReflectionConsumer {
        void accept(@Nullable Object value) throws Exception;
    }

    public static void invokeMethod(@NonNull Object target,
                                    @NonNull String methodName,
                                    @NonNull ReflectionConsumer consumer) {
        doInvoke(target, methodName, new Class<?>[0], new Object[0], consumer);
    }

    /**
     * Вызывает метод с одним {@link String}-аргументом (e.g. {@code HttpHeaders.getFirst(String)}).
     */
    public static void invokeMethod(@NonNull Object target,
                                    @NonNull String methodName,
                                    @NonNull String arg,
                                    @NonNull ReflectionConsumer consumer) {
        doInvoke(target, methodName, new Class<?>[]{ String.class }, new Object[]{ arg }, consumer);
    }

    /**
     * Вызывает метод с одним {@code int}/{@link Integer}/generic {@link Object}-аргументом.
     *
     * <p>Перебор сигнатур по убыванию специфичности:
     * <ol>
     *   <li>{@code (int)} — primitive-перегрузка;</li>
     *   <li>{@code (Integer)} — boxed-перегрузка;</li>
     *   <li>{@code (Object)} — erased generic, актуально для Resilience4j
     *       {@code IntervalFunction extends Function<Integer, Long>}: единственный
     *       reflectively-доступный {@code Method} — это {@code apply(Object)}
     *       из родительского {@link java.util.function.Function}, тогда как
     *       {@code apply(Integer)}-bridge помечен {@code synthetic} и обычно
     *       не возвращается {@link Class#getMethod}.</li>
     * </ol>
     */
    public static void invokeMethod(@NonNull Object target,
                                    @NonNull String methodName,
                                    int arg,
                                    @NonNull ReflectionConsumer consumer) {
        if (tryInvoke(target, methodName, new Class<?>[]{ int.class }, new Object[]{ arg }, consumer)) {
            return;
        }
        if (tryInvoke(target, methodName, new Class<?>[]{ Integer.class }, new Object[]{ arg }, consumer)) {
            return;
        }
        tryInvoke(target, methodName, new Class<?>[]{ Object.class }, new Object[]{ arg }, consumer);
    }

    /**
     * Извлекает HTTP status code из произвольного исключения через рефлексию.
     * Пробует {@code ex.getStatusCode()} — если возвращает {@link Integer}, берёт напрямую;
     * если объект (e.g. {@code HttpStatusCode}), вызывает {@code .value()}.
     *
     * @return status code (&gt; 0) или {@code -1}, если извлечь не удалось
     */
    public static int extractHttpStatusCode(@NonNull Throwable ex) {
        int[] holder = {-1};

        invokeMethod(ex, "getStatusCode", statusCode -> {
            if (statusCode instanceof Integer i) {
                holder[0] = i;
            } else if (statusCode != null) {
                invokeMethod(statusCode, "value", val -> {
                    if (val instanceof Integer i) {
                        holder[0] = i;
                    }
                });
            }
        });

        return holder[0];
    }

    private static void doInvoke(@NonNull Object target,
                                 @NonNull String methodName,
                                 @NonNull Class<?>[] paramTypes,
                                 @NonNull Object[] args,
                                 @NonNull ReflectionConsumer consumer) {
        tryInvoke(target, methodName, paramTypes, args, consumer);
    }

    /**
     * Аналог {@link #doInvoke}, но возвращает признак успеха
     * (метод найден и вызван без исключений, consumer тоже отработал).
     * Нужен для overload-поиска (primitive vs boxed): первый match — ОК,
     * иначе пробуем boxed-вариант, не ломая основной поток обработки.
     */
    private static boolean tryInvoke(@NonNull Object target,
                                     @NonNull String methodName,
                                     @NonNull Class<?>[] paramTypes,
                                     @NonNull Object[] args,
                                     @NonNull ReflectionConsumer consumer) {
        try {
            Method method = findAccessibleMethod(target.getClass(), methodName, paramTypes);
            if (method == null) {
                return false;
            }
            consumer.accept(method.invoke(target, args));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Ищет public-метод сначала на самом классе/его публичных предках, затем на реализуемых интерфейсах.
     */
    @Nullable
    private static Method findAccessibleMethod(@NonNull Class<?> targetClass,
                                               @NonNull String methodName,
                                               @NonNull Class<?>[] paramTypes) {
        try {
            Method direct = targetClass.getMethod(methodName, paramTypes);
            if (java.lang.reflect.Modifier.isPublic(direct.getDeclaringClass().getModifiers())) {
                return direct;
            }
        } catch (NoSuchMethodException ignored) {
            // Падать раньше времени нельзя — метод может быть объявлен только в интерфейсе.
        }

        for (Class<?> iface : collectAllInterfaces(targetClass)) {
            if (!java.lang.reflect.Modifier.isPublic(iface.getModifiers())) {
                continue;
            }
            try {
                return iface.getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException ignored) {
                // try next interface
            }
        }
        return null;
    }

    @NonNull
    private static java.util.Set<Class<?>> collectAllInterfaces(@NonNull Class<?> startClass) {
        java.util.Set<Class<?>> all = new java.util.LinkedHashSet<>();
        Class<?> current = startClass;
        while (current != null) {
            for (Class<?> iface : current.getInterfaces()) {
                if (all.add(iface)) {
                    addSuperInterfaces(iface, all);
                }
            }
            current = current.getSuperclass();
        }
        return all;
    }

    private static void addSuperInterfaces(@NonNull Class<?> iface, @NonNull java.util.Set<Class<?>> sink) {
        for (Class<?> superIface : iface.getInterfaces()) {
            if (sink.add(superIface)) {
                addSuperInterfaces(superIface, sink);
            }
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> Class<T> loadOptionalClass(@NonNull String className,
                                                 @Nullable ClassLoader classLoader) {
        ClassLoader loader = (classLoader != null) ? classLoader : ReflectionUtils.class.getClassLoader();
        try {
            return (Class<T>) Class.forName(className, false, loader);
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (IllegalAccessError err) {
            // JPMS: модуль присутствует в classpath, но недоступен по module-info.
            throw new IllegalStateException("JPMS readability mismatch for class [" + className + "]: " + err.getMessage(), err);
        } catch (LinkageError ignored) {
            // Класс найден, но несовместим из-за: duplicate definition, version conflict, NoClassDefFoundError для транзитивных зависимостей.
            // Трактуем как «недоступен» — стратегия не будет использовать данный тип.
            return null;
        }
    }
}
