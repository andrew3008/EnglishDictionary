package space.br1440.platform.tracing.autoconfigure.integrations.aop;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.annotation.Traced;
import space.br1440.platform.tracing.api.annotation.TracedAttribute;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;

import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;

/**
 * AOP-обработчик аннотации {@link Traced}.
 * <p>
 * Создаёт span для каждого вызова метода, помеченного {@code @Traced}, обогащает span атрибутами
 * из параметров, помеченных {@link TracedAttribute}, и автоматически переводит исключения в
 * {@link SpanResult#FAILURE} с записью exception-события.
 */
@Slf4j
@Aspect
public class TracedAspect {

    private final TraceOperations traceOperations;
    private final ExceptionRecorder exceptionRecorder;

    private final Set<String> httpServerMisuseWarnedOnce = ConcurrentHashMap.newKeySet();

    public TracedAspect(TraceOperations traceOperations) {
        this(traceOperations, ExceptionRecorder.secureDefault());
    }

    public TracedAspect(TraceOperations traceOperations, ExceptionRecorder exceptionRecorder) {
        this.traceOperations = traceOperations;
        this.exceptionRecorder = (exceptionRecorder == null) ? ExceptionRecorder.secureDefault() : exceptionRecorder;
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

        if (annotation.category() == SpanCategory.HTTP_SERVER) {
            String key = method.getDeclaringClass().getName() + "#" + method.getName();
            if (httpServerMisuseWarnedOnce.add(key)) {
                log.warn("""
                        @Traced(category=HTTP_SERVER) on method %s almost always \
                        duplicates HTTP-server spans. HTTP-server spans are created by \
                        OpenTelemetry Java Agent (Tomcat/Netty) and Micrometer Observation. \
                        Use @Traced(category=INTERNAL) or RPC for business operations."""
                        .formatted(key));
            }
        }

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

            String key = attribute.value().isBlank() ? parameter.getName() : attribute.value();
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

        throw new IllegalStateException("Аннотация @Traced не найдена ни на методе, ни на классе: " + method);
    }

    private String resolveSpanName(Traced annotation, Method method) {
        String declared = annotation.value();
        return declared.isBlank() ? method.getName() : declared;
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
