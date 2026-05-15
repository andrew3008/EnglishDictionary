package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.lang.Nullable;

@UtilityClass
public final class ClassUtils {

    public static boolean isPresent(String className, @Nullable ClassLoader classLoader) {
        try {
            ClassLoader loader = (classLoader != null) ? classLoader : ClassUtils.class.getClassLoader();
            Class.forName(className, false, loader);
            return true;
        } catch (IllegalAccessError err) {
            throw new IllegalStateException("Readability mismatch in inheritance hierarchy of class [" + className + "]: " + err.getMessage(), err);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isInstanceOf(@Nullable Class<?> clazz, @Nullable Object obj) {
        return clazz != null && clazz.isInstance(obj);
    }
}
