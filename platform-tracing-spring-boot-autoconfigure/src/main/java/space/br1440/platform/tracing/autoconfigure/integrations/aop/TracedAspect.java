package space.br1440.platform.tracing.autoconfigure.integrations.aop;

import io.opentelemetry.api.trace.Span;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AOP-обработчик аннотации {@link Traced}.
 * <p>
 * Создаёт span для каждого вызова метода, помеченного {@code @Traced}, обогащает span атрибутами
 * из параметров, помеченных {@link TracedAttribute}, и автоматически переводит исключения в
 * {@link SpanResult#FAILURE} с записью exception-события.
 * <p>
 * Режим работы — всегда {@code ENRICH_CURRENT}: если есть активный валидный span (например,
 * серверный, созданный OpenTelemetry Java Agent), аспект обогащает его вместо создания дочернего.
 * Это устраняет дублирующие span'ы при совместной работе с Agent. Legacy-режим создания
 * отдельного дочернего span'а независимо от родителя удалён как источник того же дублирования,
 * от которого аспект защищает в остальных случаях (см. {@code ADR-spring-owns-only-what-spring-applies}).
 * <p>
 * Используется Spring AOP (proxy-based). Прикладные сервисы должны быть Spring-бинами; вызов через
 * прямой ссылку на {@code this} не перехватывается — это ограничение proxy-подхода. Если требуется
 * перехват внутри класса, рекомендуется выделить метод в отдельный бин.
 */
@Aspect
public class TracedAspect {

    private static final Logger log = LoggerFactory.getLogger(TracedAspect.class);

    private final TraceOperations traceOperations;
    private final ExceptionRecorder exceptionRecorder;

    /**
     * Multiset методов, для которых WARN о некорректном использовании
     * {@code @Traced(category=HTTP_SERVER)} уже выводился. Гарантирует «один WARN на сигнатуру»
     * при множественных вызовах одного и того же эндпоинта.
     */
    private final Set<String> httpServerMisuseReported = ConcurrentHashMap.newKeySet();

    public TracedAspect(TraceOperations traceOperations) {
        this(traceOperations, ExceptionRecorder.secureDefault());
    }

    public TracedAspect(TraceOperations traceOperations, ExceptionRecorder exceptionRecorder) {
        this.traceOperations = traceOperations;
        this.exceptionRecorder = exceptionRecorder == null ? ExceptionRecorder.secureDefault() : exceptionRecorder;
    }

    @Around("@annotation(space.br1440.platform.tracing.api.annotation.Traced) "
            + "|| @within(space.br1440.platform.tracing.api.annotation.Traced)")
    public Object aroundTraced(ProceedingJoinPoint joinPoint) throws Throwable {
        Method invokedMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        // При вызове через интерфейс invokedMethod — метод интерфейса; аннотация может находиться
        // только на методе реализации. Используем Spring helper, чтобы получить «самый специфичный» метод.
        Class<?> targetClass = joinPoint.getTarget() != null
                ? joinPoint.getTarget().getClass()
                : invokedMethod.getDeclaringClass();
        Method method = AopUtils.getMostSpecificMethod(invokedMethod, targetClass);
        Traced annotation = resolveAnnotation(method);
        String spanName = resolveSpanName(annotation, method);

        // Одноразовый WARN: @Traced(category=HTTP_SERVER) на прикладном методе — почти всегда
        // ошибка, потому что HTTP-server span создаётся либо OTel Java Agent (на уровне Tomcat),
        // либо Micrometer Observation. Создание HTTP-server span'а в @Traced-аспекте даёт
        // дублирование. Используем @Traced(category=INTERNAL/RPC) для бизнес-операций.
        if (annotation.category() == SpanCategory.HTTP_SERVER) {
            String key = method.getDeclaringClass().getName() + "#" + method.getName();
            if (httpServerMisuseReported.add(key)) {
                log.warn("@Traced(category=HTTP_SERVER) on method {} almost always "
                        + "duplicates HTTP-server spans. HTTP-server spans are created by "
                        + "OpenTelemetry Java Agent (Tomcat/Netty) and Micrometer Observation. "
                        + "Use @Traced(category=INTERNAL) or RPC for business operations.", key);
            }
        }

        // Если есть активный валидный span (например, серверный, созданный OpenTelemetry Java
        // Agent), не создаём дочерний — обогащаем текущий. Это устраняет дублирующие span'ы при
        // работе вместе с Agent (распространённая ошибка инструментации прикладного кода поверх
        // auto-instrumentation) и удешевляет трассировку.
        if (hasActiveSpan()) {
            return enrichCurrentSpan(joinPoint, method, spanName);
        }

        // Отсутствие активного контекста: создаём отдельный span штатным путём.
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

    /**
     * Обогащает уже активный span атрибутами (без создания дочернего scope'а).
     * <p>
     * Записывает атрибуты {@code @TracedAttribute}-параметров, имя {@code @Traced}-метода
     * (как обычный атрибут {@code platform.traced.method}), а в случае исключения вызывает
     * {@code recordException} на текущем span'е через {@link ExceptionRecorder}.
     * Сам span при этом НЕ закрывается — его жизненным циклом владеет внешний слой
     * (HTTP-фильтр / OTel Agent / сторонняя инструментация).
     */
    private Object enrichCurrentSpan(ProceedingJoinPoint joinPoint, Method method, String spanName)
            throws Throwable {
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

    /**
     * Возвращает {@code true}, если в OpenTelemetry-контексте есть валидный активный span.
     */
    private static boolean hasActiveSpan() {
        return Span.current().getSpanContext().isValid();
    }

    /**
     * Записывает значения {@code @TracedAttribute}-параметров на переданный span (для ENRICH_CURRENT-режима).
     */
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

    /**
     * Аннотация {@link Traced} может быть на методе или на классе. В первую очередь учитывается метод.
     */
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

    /**
     * Имя span'а: значение в аннотации, иначе простое имя метода.
     */
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
