package space.br1440.platform.errorhandling.core.strategy.cause.core;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Root-cause стратегия для Jakarta Bean Validation {@code ConstraintViolationException}.
 *
 * <h2>Область применения</h2>
 * <p>Стратегия покрывает <b>сервис-слой</b>: программный вызов
 * {@code Validator#validate(...)} либо {@code @Valid} на параметрах
 * {@code @Service}-методов, поднимающих {@link ConstraintViolationException}
 * как root-cause бизнес-исключения.
 *
 * <p>Контроллер-binding ошибки ({@code MethodArgumentNotValidException},
 * {@code HandlerMethodValidationException}, {@code BindException}) обрабатываются
 * top-level стратегией {@code SpringMvcExceptionStrategy} в модуле
 * {@code error-handling-mvc} и сюда не доходят.
 *
 * <h2>Поддерживаемые типы</h2>
 * <ul>
 *   <li><b>{@link jakarta.validation.ConstraintViolationException}</b> — основной путь,
 *       прямой type-safe cast, без reflection.</li>
 *   <li><b>{@code javax.validation.ConstraintViolationException}</b> (legacy 2.x) —
 *       classpath-optional fallback через reflection. Если Set вернётся с не-jakarta
 *       элементами (mixed classpath), они отфильтровываются молча — см.
 *       {@link #extractViolations}.</li>
 * </ul>
 *
 * <p>Произвольные исключения по суффиксу имени ({@code *ValidationException})
 * <b>намеренно не поддерживаются</b>: такой контракт давал массовый false-positive
 * (перехват {@code XmlValidationException}, {@code CertificateValidationException},
 * {@code SamlValidationException} и прочих инфраструктурных ошибок → искажение 4xx/5xx
 * и SLO). Кастомные бизнес-исключения, требующие 400-семантики, должны регистрировать
 * собственную стратегию через SPI.
 *
 * <h2>Безопасность (CWE-209, CWE-312, CWE-117, CWE-400)</h2>
 * <p>Намеренно <b>не включаются</b> в metadata:
 * <ul>
 *   <li>{@link ConstraintViolation#getInvalidValue()} — может содержать PII, пароли,
 *       токены, номера карт.</li>
 *   <li>{@link ConstraintViolation#getLeafBean()} — раскрывает внутреннюю структуру
 *       доменной модели.</li>
 *   <li>Полный FQCN {@code rootBeanClass} — используется {@link Class#getSimpleName()}.</li>
 * </ul>
 */
@Slf4j
public class BeanValidationExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final ClassLoader LOADER = BeanValidationExceptionStrategy.class.getClassLoader();

    /**
     * Legacy {@code javax.validation.ConstraintViolationException} (Bean Validation 2.x).
     * Classpath-optional: загружается только при наличии в classpath, reflection
     * применяется исключительно для извлечения {@code getConstraintViolations()}.
     */
    private static final Class<?> JAVAX_CONSTRAINT_VIOLATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("javax.validation.ConstraintViolationException", LOADER);

    private static final String REASON = "BEAN_VALIDATION";
    private static final String DOMAIN = "validation";

    static final int MAX_VIOLATIONS_IN_METADATA = 20;
    static final int MAX_VIOLATIONS_TO_SCAN = 200;
    static final int MAX_VIOLATION_MESSAGE_LENGTH = 150;
    static final int MAX_VIOLATED_CONSTRAINTS_LENGTH = 2048;
    static final int MAX_PROPERTY_PATH_LENGTH = 100;

    public BeanValidationExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.BEAN_VALIDATION, stackHintExtractor);
    }

    /**
     * Нарушения Bean Validation — ожидаемые клиентские события, но в сервисном слое
     * они чаще всего означают некорректный обход валидации на входе (баг вызывающего кода).
     * {@link LogPolicy#WARN} — требуют внимания, но не являются инфраструктурными сбоями.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry errorEntry) {
        return LogPolicy.WARN;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return cause instanceof ConstraintViolationException
                || ClassUtils.isInstanceOf(JAVAX_CONSTRAINT_VIOLATION_EXCEPTION, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        Set<ConstraintViolation<?>> violations = extractViolations(cause);

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(REASON)
                .domain(DOMAIN)
                .diagnosticMetadata(buildDiagnosticMetadata(violations, cause))
                .build();

        return RootCauseResolution.notRetryable(
                HttpStatus.BAD_REQUEST,
                buildMessage(violations),
                List.of(info));
    }

    @NonNull
    private static Set<ConstraintViolation<?>> extractViolations(@NonNull Throwable cause) {
        if (cause instanceof ConstraintViolationException jakartaEx) {
            Set<ConstraintViolation<?>> jakartaViolations = jakartaEx.getConstraintViolations();
            return (jakartaViolations != null) ? jakartaViolations : Collections.emptySet();
        }

        Object[] result = {null};
        ReflectionUtils.invokeMethod(cause, "getConstraintViolations", val -> result[0] = val);

        if (result[0] instanceof Set<?> raw) {
            return raw.stream()
                    .filter(ConstraintViolation.class::isInstance)
                    .map(v -> (ConstraintViolation<?>) v)
                    .collect(Collectors.toSet());
        }

        log.warn("Bean Validation exception [{}] не предоставил getConstraintViolations(); диагностика деградирована",
                LogSanitizer.sanitize(cause.getClass().getName()));
        return Collections.emptySet();
    }

    @NonNull
    private static String buildMessage(@NonNull Set<ConstraintViolation<?>> violations) {
        if (violations.isEmpty()) {
            return "Validation failed.";
        }

        return "Validation failed: " + violations.size() + " constraint violations detected.";
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Set<ConstraintViolation<?>> violations,
                                                        @NonNull Throwable cause) {
        ViolationsReport report = violations.isEmpty() ? ViolationsReport.empty() : summarize(violations);

        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor)
                .put("violationCount", String.valueOf(violations.size()))
                .putIfPresent("violatedConstraints", report.violatedConstraints)
                .putIfPresent("rootBeanClass", report.rootBeanClass);

        if (report.truncated) {
            builder.put("violationsTruncated", "true");
        }

        return builder.withStackHint().build();
    }

    @NonNull
    private static ViolationsReport summarize(@NonNull Set<ConstraintViolation<?>> violations) {
        List<String> entries = new ArrayList<>(Math.min(violations.size(), MAX_VIOLATIONS_IN_METADATA));

        Map<String, Integer> rootBeans = new HashMap<>();
        int totalLength = 0;
        int scanned = 0;
        boolean truncated = false;

        for (ConstraintViolation<?> violation : violations) {
            if (scanned++ >= MAX_VIOLATIONS_TO_SCAN) {
                truncated = true;
                break;
            }

            if (violation == null) {
                continue;
            }

            trackRootBean(violation, rootBeans);

            if (entries.size() >= MAX_VIOLATIONS_IN_METADATA) {
                truncated = true;
                continue;
            }

            String entry = formatViolation(violation);
            int projected = totalLength + (totalLength == 0 ? 0 : 1) + entry.length();
            if (projected > MAX_VIOLATED_CONSTRAINTS_LENGTH) {
                truncated = true;
                continue;
            }

            entries.add(entry);
            totalLength = projected;
        }

        String violatedConstraints = entries.isEmpty() ? null : String.join(",", entries);
        String rootBean = resolveRootBean(rootBeans);

        return new ViolationsReport(violatedConstraints, rootBean, truncated);
    }

    @NonNull
    private static String formatViolation(@NonNull ConstraintViolation<?> violation) {
        String path = extractPropertyPath(violation);
        String type = extractConstraintType(violation);

        String rawMessage = violation.getMessage();
        String message = StringUtils.hasText(rawMessage)
                ? LogSanitizer.sanitize(rawMessage, MAX_VIOLATION_MESSAGE_LENGTH)
                : null;

        return orUnknown(path) + ":" + orUnknown(type) + ":" + orUnknown(message);
    }

    @NonNull
    private static String orUnknown(@Nullable String value) {
        return StringUtils.hasText(value) ? value : "?";
    }

    private static String extractPropertyPath(@NonNull ConstraintViolation<?> violation) {
        Path path = violation.getPropertyPath();
        if (path == null) {
            return null;
        }

        String rendered = path.toString();
        if (!StringUtils.hasText(rendered)) {
            return null;
        }

        return LogSanitizer.sanitize(rendered, MAX_PROPERTY_PATH_LENGTH);
    }

    private static String extractConstraintType(@NonNull ConstraintViolation<?> violation) {
        ConstraintDescriptor<?> descriptor = violation.getConstraintDescriptor();
        if (descriptor == null) {
            return null;
        }

        Annotation annotation = descriptor.getAnnotation();
        if (annotation == null) {
            return null;
        }

        return annotation.annotationType().getSimpleName();
    }

    private static void trackRootBean(@NonNull ConstraintViolation<?> violation,
                                      @NonNull Map<String, Integer> counters) {
        Class<?> rootBeanClass = violation.getRootBeanClass();
        if (rootBeanClass == null) {
            return;
        }

        counters.merge(rootBeanClass.getSimpleName(), 1, Integer::sum);
    }

    private static String resolveRootBean(@NonNull Map<String, Integer> counters) {
        if (counters.isEmpty()) {
            return null;
        }

        if (counters.size() == 1) {
            return counters.keySet().iterator().next();
        }

        return "MULTIPLE";
    }

    private record ViolationsReport(String violatedConstraints,
                                    String rootBeanClass,
                                    boolean truncated) {

        static ViolationsReport empty() {
            return new ViolationsReport(null, null, false);
        }
    }
}
