package space.br1440.platform.leaderelection.validation;

import space.br1440.platform.leaderelection.annotation.locking.DistributedLock;
import space.br1440.platform.leaderelection.annotation.locking.SchedulerLock;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Set;

@SupportedAnnotationTypes({
        "space.br1440.platform.leaderelection.annotation.locking.SchedulerLock",
        "space.br1440.platform.leaderelection.annotation.locking.DistributedLock"
})
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public class LockingValidationProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(DistributedLock.class)) {
            if (element.getKind() == ElementKind.METHOD) {
                validateDistributedLockUsage(element);
                validateNoConflictingAnnotations(element);
                validateDistributedLockTimings(element);
            }
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(SchedulerLock.class)) {
            if (element.getKind() == ElementKind.METHOD) {
                validateNoConflictingAnnotations(element);
                validateSchedulerLockTimings(element);
            }
        }

        return true;
    }

    private void validateDistributedLockUsage(Element methodElement) {
        boolean hasScheduled = methodElement.getAnnotationMirrors().stream()
                .anyMatch(mirror -> mirror.getAnnotationType().toString().contains("Scheduled"));

        if (hasScheduled) {
            String message = """
                       Method %s has both @Scheduled and @DistributedLock annotations. \
                       Use @SchedulerLock instead of @DistributedLock for scheduled methods.
                    """
                    .formatted(methodElement.getSimpleName());

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    message,
                    methodElement
            );
        }
    }

    private void validateNoConflictingAnnotations(Element methodElement) {
        boolean hasDistributedLock = methodElement.getAnnotationMirrors().stream()
                .anyMatch(mirror -> mirror.getAnnotationType().toString().contains("DistributedLock"));

        boolean hasSchedulerLock = methodElement.getAnnotationMirrors().stream()
                .anyMatch(mirror -> mirror.getAnnotationType().toString().contains("SchedulerLock"));

        if (hasDistributedLock && hasSchedulerLock) {
            String message = """
                       Method %s.%s cannot have both @DistributedLock and @SchedulerLock annotations. \
                       Use only one locking annotation per method.
                    """
                    .formatted(
                            methodElement.getEnclosingElement().getSimpleName(),
                            methodElement.getSimpleName()
                    );

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    message,
                    methodElement
            );
        }
    }

    private void validateDistributedLockTimings(Element methodElement) {
        DistributedLock annotation = methodElement.getAnnotation(DistributedLock.class);
        validateLockTimings(
                methodElement,
                annotation.lockAtMostFor(),
                annotation.lockAtLeastFor(),
                "DistributedLock"
        );
    }

    private void validateSchedulerLockTimings(Element methodElement) {
        SchedulerLock annotation = methodElement.getAnnotation(SchedulerLock.class);
        validateLockTimings(
                methodElement,
                annotation.lockAtMostFor(),
                annotation.lockAtLeastFor(),
                "SchedulerLock"
        );
    }

    private void validateLockTimings(Element methodElement,
                                     String lockAtMostForStr,
                                     String lockAtLeastForStr,
                                     String annotationType) {
        if (isDynamic(lockAtMostForStr) || isDynamic(lockAtLeastForStr)) {
            return;
        }

        if (isBlank(lockAtMostForStr) || isBlank(lockAtLeastForStr)) {
            return;
        }

        try {
            Duration atMostFor = Duration.parse(lockAtMostForStr);
            Duration atLeastFor = Duration.parse(lockAtLeastForStr);

            if (atLeastFor.compareTo(atMostFor) > 0) {
                String message = """
                        @%s: lockAtLeastFor (%s) cannot be greater than lockAtMostFor (%s). \
                        The task cannot hold the lock for at least %s when the maximum allowed time is %s. \
                        Please adjust the timing configuration for %s.%s()
                        """
                        .formatted(
                                annotationType,
                                lockAtLeastForStr,
                                lockAtMostForStr,
                                atLeastFor,
                                atMostFor,
                                methodElement.getEnclosingElement().getSimpleName(),
                                methodElement.getSimpleName()
                        );

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        message,
                        methodElement
                );
            }

            if (atLeastFor.isNegative()) {
                String message = """
                        @%s: lockAtLeastFor cannot be negative: %s. \
                        Please provide a non-negative duration for %s.%s()
                        """
                        .formatted(
                                annotationType,
                                lockAtLeastForStr,
                                methodElement.getEnclosingElement().getSimpleName(),
                                methodElement.getSimpleName()
                        );

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        message,
                        methodElement
                );
            }

            if (atMostFor.isNegative() || atMostFor.isZero()) {
                String message = """
                        @%s: lockAtMostFor must be positive: %s. \
                        Please provide a positive duration for %s.%s()
                        """
                        .formatted(
                                annotationType,
                                lockAtMostForStr,
                                methodElement.getEnclosingElement().getSimpleName(),
                                methodElement.getSimpleName()
                        );

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        message,
                        methodElement
                );
            }

            Duration oneDay = Duration.ofDays(1);
            if (atMostFor.compareTo(oneDay) > 0) {
                String message = """
                        @%s: lockAtMostFor is unusually long: %s (more than 1 day). \
                        Consider reviewing the timing configuration for %s.%s()
                        """
                        .formatted(
                                annotationType,
                                lockAtMostForStr,
                                methodElement.getEnclosingElement().getSimpleName(),
                                methodElement.getSimpleName()
                        );

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        message,
                        methodElement
                );
            }

        } catch (DateTimeParseException e) {
            String message = """
                    @%s: Invalid Duration format for lock timings in %s.%s(). \
                    lockAtMostFor: '%s', lockAtLeastFor: '%s'. \
                    Please use ISO-8601 Duration format (e.g., '30s', '5m', '1h'). \
                    Error: %s
                    """
                    .formatted(
                            annotationType,
                            methodElement.getEnclosingElement().getSimpleName(),
                            methodElement.getSimpleName(),
                            lockAtMostForStr,
                            lockAtLeastForStr,
                            e.getMessage()
                    );

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    message,
                    methodElement
            );
        }
    }

    private boolean isDynamic(String value) {
        return value != null && (value.contains("${") || value.contains("#{"));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}