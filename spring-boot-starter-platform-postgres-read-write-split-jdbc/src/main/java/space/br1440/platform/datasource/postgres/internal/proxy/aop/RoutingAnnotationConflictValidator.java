package space.br1440.platform.datasource.postgres.internal.proxy.aop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;
import space.br1440.platform.datasource.postgres.api.routing.UseReadReplica;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Проверяет на старте приложения, что {@link UseReadReplica} и {@link Transactional}
 * не применяются одновременно к одному методу: такое дублирование неоднозначно,
 * поскольку оба механизма управляют выбором DataSource.
 */
@Slf4j
public class RoutingAnnotationConflictValidator implements SmartInitializingSingleton {

    private static final String RESOLUTION_HINT = "Use @Transactional(readOnly = true) instead of combining both annotations.";

    private final ListableBeanFactory beanFactory;

    public RoutingAnnotationConflictValidator(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> conflicts = findConflicts();
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(buildErrorMessage(conflicts));
        }

        log.debug("Routing annotation conflict validation passed — no @UseReadReplica + @Transactional conflicts found.");
    }

    private List<String> findConflicts() {
        Set<Class<?>> visitedClasses = new HashSet<>();
        List<String> conflicts = new ArrayList<>();

        Collection<Object> beans = beanFactory.getBeansOfType(Object.class, false, false).values();
        for (Object bean : beans) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            if (!visitedClasses.add(targetClass)) {
                continue;
            }

            collectConflictsForClass(targetClass, conflicts);
        }

        return conflicts;
    }

    private void collectConflictsForClass(Class<?> targetClass, List<String> conflicts) {
        ReflectionUtils.doWithMethods(
                targetClass,
                method -> {
                    if (hasRoutingConflict(method)) {
                        conflicts.add(formatConflict(targetClass, method));
                    }
                },
                RoutingAnnotationConflictValidator::isUserDeclaredMethod
        );
    }

    private static boolean hasRoutingConflict(Method method) {
        return hasAnnotation(method, UseReadReplica.class)
                && hasAnnotation(method, Transactional.class);
    }

    private static boolean hasAnnotation(Method method, Class<? extends Annotation> type) {
        return (AnnotationUtils.findAnnotation(method, type) != null);
    }

    private static boolean isUserDeclaredMethod(Method method) {
        return !method.isSynthetic() && !method.isBridge();
    }

    private static String formatConflict(Class<?> targetClass, Method method) {
        return targetClass.getName() + "#" + method.getName() + "(" + formatParameterTypes(method) + ")";
    }

    private static String formatParameterTypes(Method method) {
        return Stream.of(method.getParameterTypes())
                .map(Class::getSimpleName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String buildErrorMessage(List<String> conflicts) {
        StringBuilder builder = new StringBuilder(256)
                .append("@UseReadReplica and @Transactional cannot coexist on the same method. ")
                .append("Found ").append(conflicts.size()).append(" conflict(s):");

        for (String conflict : conflicts) {
            builder.append(System.lineSeparator()).append("  - ").append(conflict);
        }

        builder.append(System.lineSeparator()).append(RESOLUTION_HINT);

        return builder.toString();
    }
}
