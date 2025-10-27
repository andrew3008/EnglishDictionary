package space.br1440.platform.leaderelection.util;

import lombok.experimental.UtilityClass;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@UtilityClass
public class AnnotationUtils {

    public static void makeAccessible(AccessibleObject member) {
        switch (member) {
            case Field f -> ReflectionUtils.makeAccessible(f);
            case Method m -> ReflectionUtils.makeAccessible(m);
            case Constructor<?> c -> ReflectionUtils.makeAccessible(c);
            case null, default -> throw new IllegalArgumentException("Unsupported AccessibleObject: " + member);
        }
    }

}
