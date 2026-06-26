package space.br1440.platform.errorhandling.core.util;

import lombok.Getter;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Извлекает диагностический стек-хинт из {@link Throwable}.
 *
 * <p>Стек-хинт — 1-3 значимых фрейма стектрейса, отфильтрованных от
 * proxy/reflection/Spring AOP/cglib/servlet container. Помогает быстрее
 * понять контекст ошибки без полного стектрейса.
 *
 * <h3>Жизненный цикл</h3>
 * <p>Управляется Spring-контейнером как singleton bean. Регистрируется в
 * {@code ErrorHandlingCoreConfiguration} с {@code @ConditionalOnMissingBean},
 * что позволяет микросервисам переопределить bean с кастомными noise-префиксами:
 * <pre>{@code
 * @Bean
 * public StackHintExtractor stackHintExtractor() {
 *     return new StackHintExtractor(true, List.of("com.mycompany.internal."));
 * }
 * }</pre>
 *
 * <h3>Thread-safety</h3>
 * <p>Immutable после создания. Безопасен для конкурентного использования
 * из нескольких потоков без синхронизации.
 *
 * @see DiagnosticMetadataBuilder#withStackHint()
 * @see space.br1440.platform.errorhandling.extension.ErrorHandlingContext#getStackHintExtractor()
 */
public final class StackHintExtractor {

    private static final int MAX_HINT_FRAMES = 3;
    private static final int MAX_CAUSE_DEPTH = CauseChainNavigator.DEFAULT_MAX_DEPTH;
    private static final String FRAME_DELIMITER = " | ";

    private static final String[] DEFAULT_NOISE_PREFIXES = {
            "sun.reflect.",
            "java.lang.reflect.",
            "jdk.internal.reflect.",
            "org.springframework.cglib.",
            "org.springframework.aop.",
            "org.springframework.web.servlet.",
            "org.springframework.web.filter.",
            "org.springframework.security.",
            "net.sf.cglib.",
            "com.sun.proxy.",
            "jakarta.servlet.",
            "java.util.concurrent.",
            "org.junit.",
            "org.mockito.",
            "java.lang.Thread",
            "org.apache.catalina.",
            "org.apache.tomcat.",
    };

    @Getter
    private final boolean enabled;
    private final String[] noisePrefixes;

    public StackHintExtractor(boolean enabled, @NonNull List<String> additionalNoisePrefixes) {
        this.enabled = enabled;
        String[] merged = Arrays.copyOf(
                DEFAULT_NOISE_PREFIXES,
                DEFAULT_NOISE_PREFIXES.length + additionalNoisePrefixes.size()
        );

        for (int i = 0; i < additionalNoisePrefixes.size(); i++) {
            merged[DEFAULT_NOISE_PREFIXES.length + i] = additionalNoisePrefixes.get(i);
        }

        this.noisePrefixes = merged;
    }

    @Nullable
    public String extractStackHint(@NonNull Throwable throwable) {
        if (!enabled) {
            return null;
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        int depth = 0;

        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            String hint = extractFromSingleThrowable(current);
            if (hint != null) {
                return hint;
            }

            current = current.getCause();
            depth++;
        }

        return null;
    }

    @Nullable
    private String extractFromSingleThrowable(@NonNull Throwable throwable) {
        StackTraceElement[] elements = throwable.getStackTrace();
        if (elements == null || elements.length == 0) {
            return null;
        }

        List<String> frames = new ArrayList<>(MAX_HINT_FRAMES);
        for (StackTraceElement element : elements) {
            if (frames.size() >= MAX_HINT_FRAMES) {
                break;
            }

            if (!isNoise(element.getClassName())) {
                frames.add(formatFrame(element));
            }
        }

        return frames.isEmpty() ? null : String.join(FRAME_DELIMITER, frames);
    }

    private boolean isNoise(@NonNull String className) {
        for (String prefix : noisePrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    @NonNull
    private static String formatFrame(@NonNull StackTraceElement element) {
        String simpleClass = simpleClassName(element.getClassName());
        String method = element.getMethodName();

        if (element.isNativeMethod()) {
            return simpleClass + "." + method + "(Native Method)";
        }

        String file = element.getFileName();
        int line = element.getLineNumber();
        if (file != null && line > 0) {
            return simpleClass + "." + method + "(" + file + ":" + line + ")";
        }

        return simpleClass + "." + method + "(Unknown Source)";
    }

    @NonNull
    private static String simpleClassName(@NonNull String className) {
        int lastDot = className.lastIndexOf('.');
        String simple = (lastDot >= 0) ? className.substring(lastDot + 1) : className;
        return simple.replace('$', '.');
    }
}
