package space.br1440.platform.tracing.autoconfigure.integrations.aop;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.annotation.Traced;
import space.br1440.platform.tracing.api.annotation.TracedAttribute;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;

import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;

/**
 * AOP-обработчик аннотации {@link Traced}.
 * <p>
 * <b>Create-path.</b> При отсутствии valid {@code Span.current()} создаёт INTERNAL-операционный span
 * через {@code spans().operation(name)}, записывает {@link TracedAttribute} и закрывает handle в
 * try-with-resources на выходе из метода.
 * <p>
 * <b>Enrich-path.</b> При активном span child не создаётся: пишутся {@code platform.traced.method}
 * и {@link TracedAttribute}.
 * <p>
 * <b>Ordering.</b> {@link Ordered#HIGHEST_PRECEDENCE}{@code + 1} — span охватывает
 * Kafka request-identity aspect и transaction advice с дефолтным {@link Ordered#LOWEST_PRECEDENCE}.
 * <p>
 * <b>Ограничения.</b> Только публичные не-{@code final} методы Spring-бинов при внешнем вызове;
 * self-invocation — silent no-op. Async/reactive возвращаемые типы и {@code @Async} запрещены
 * ArchUnit-правилом (см. {@code ADR-traced-annotation-contract}). Аспект не усекает значения
 * атрибутов и не конфигурирует OTel SDK.
 */
@Slf4j
@Aspect
public class TracedAspect implements Ordered {

    public static final int ORDER = (Ordered.HIGHEST_PRECEDENCE + 1);

    private final TraceOperations traceOperations;
    private final ExceptionRecorder exceptionRecorder;

    public TracedAspect(TraceOperations traceOperations) {
        this(traceOperations, ExceptionRecorder.secureDefault());
    }

    public TracedAspect(TraceOperations traceOperations, ExceptionRecorder exceptionRecorder) {
        this.traceOperations = traceOperations;
        this.exceptionRecorder = (exceptionRecorder == null) ? ExceptionRecorder.secureDefault() : exceptionRecorder;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Around("@annotation(space.br1440.platform.tracing.api.annotation.Traced) "
            + "|| @within(space.br1440.platform.tracing.api.annotation.Traced)")
    public Object aroundTraced(ProceedingJoinPoint joinPoint) throws Throwable {
        Method invokedMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();

        Class<?> targetClass = (joinPoint.getTarget() != null)
                ? joinPoint.getTarget().getClass()
                : invokedMethod.getDeclaringClass();
        Method method = AopUtils.getMostSpecificMethod(invokedMethod, targetClass);
        Traced annotation = resolveAnnotation(method);
        String spanName = resolveSpanName(annotation, method);

        if (hasActiveSpan()) {
            return enrichCurrentSpan(joinPoint, method, spanName);
        }

        try (SpanHandle handle = traceOperations.spans().operation(spanName).start()) {
            applyParameterAttributesToSpan(Span.current(), method, joinPoint.getArgs());

            try {
                Object result = joinPoint.proceed();
                Span.current().setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
                return result;
            } catch (Throwable t) {
                try {
                    handle.recordException(t);
                } catch (RuntimeException tracingError) {
                    log.warn("Failed to record exception on span '{}': {}",
                            spanName, tracingError.getMessage());
                }

                throw t;
            }
        }
    }

    private Object enrichCurrentSpan(ProceedingJoinPoint joinPoint, Method method, String spanName) throws Throwable {
        Span current = Span.current();
        try {
            current.setAttribute(PlatformAttributes.PLATFORM_TRACED_METHOD, spanName);
            applyParameterAttributesToSpan(current, method, joinPoint.getArgs());
        } catch (RuntimeException attrError) {
            log.warn("Failed to enrich active span '{}' via @Traced: {}",
                    spanName, attrError.getMessage());
        }

        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            try {
                exceptionRecorder.record(Span.current(), t);
            } catch (RuntimeException tracingError) {
                log.warn("Failed to record exception on active span via @Traced('{}'): {}",
                        spanName, tracingError.getMessage());
            }

            throw t;
        }
    }

    private static boolean hasActiveSpan() {
        return Span.current().getSpanContext().isValid();
    }

    private static void applyParameterAttributesToSpan(Span span, Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0 || args == null || args.length != parameters.length) {
            return;
        }

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            TracedAttribute attribute = findTracedAttribute(parameter);
            if (attribute == null) {
                continue;
            }

            String key = StringUtils.hasText(attribute.value()) ? attribute.value() : parameter.getName();
            Object value = args[i];
            if (value != null) {
                span.setAttribute(key, String.valueOf(value));
            }
        }
    }

    private Traced resolveAnnotation(Method method) {
        Traced onMethod = AnnotationUtils.findAnnotation(method, Traced.class);
        if (onMethod != null) {
            return onMethod;
        }

        Traced onClass = AnnotationUtils.findAnnotation(method.getDeclaringClass(), Traced.class);
        if (onClass != null) {
            return onClass;
        }

        throw new IllegalStateException("@Traced annotation not found on method or declaring class: " + method);
    }

    private String resolveSpanName(Traced annotation, Method method) {
        String declared = annotation.value();
        return StringUtils.hasText(declared) ? declared : method.getName();
    }

    private static TracedAttribute findTracedAttribute(Parameter parameter) {
        for (Annotation annotation : parameter.getAnnotations()) {
            if (annotation instanceof TracedAttribute tracedAttribute) {
                return tracedAttribute;
            }
        }

        return null;
    }
}
