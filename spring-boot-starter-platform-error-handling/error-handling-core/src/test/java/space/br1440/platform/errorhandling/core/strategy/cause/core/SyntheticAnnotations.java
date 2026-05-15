package space.br1440.platform.errorhandling.core.strategy.cause.core;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Фабрика тестовых аннотаций, симулирующих поведение Hibernate Validator: прокси-объект
 * возвращает настоящий {@link Annotation#annotationType()} в противоположность имени
 * proxy-класса (которое в runtime выглядит как {@code $Proxy42} / {@code NotNullImpl}).
 */
final class SyntheticAnnotations {

    private static final Map<String, Class<? extends Annotation>> CACHE = new ConcurrentHashMap<>();

    private SyntheticAnnotations() {
    }

    static Annotation of(String simpleName) {
        Class<? extends Annotation> annotationType = resolve(simpleName);
        return (Annotation) Proxy.newProxyInstance(
                SyntheticAnnotations.class.getClassLoader(),
                new Class<?>[]{ annotationType },
                (proxy, method, args) -> {
                    if ("annotationType".equals(method.getName())) {
                        return annotationType;
                    }
                    if ("toString".equals(method.getName())) {
                        return "@" + annotationType.getName() + "()";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return 0;
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                });
    }

    private static Class<? extends Annotation> resolve(String simpleName) {
        return CACHE.computeIfAbsent(simpleName, SyntheticAnnotations::loadSynthetic);
    }

    private static Class<? extends Annotation> loadSynthetic(String simpleName) {
        return switch (simpleName) {
            case "NotNull"  -> NotNull.class;
            case "Min"      -> Min.class;
            case "NotBlank" -> NotBlank.class;
            case "Email"    -> Email.class;
            case "Size"     -> Size.class;
            default -> Custom.class;
        };
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface NotNull {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Min {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface NotBlank {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Email {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Size {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Custom {
    }
}
