package space.br1440.platform.logging.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.aop.framework.AopProxyUtils;
import space.br1440.platform.logging.annotation.MDCLogKey;
import space.br1440.platform.logging.annotation.Masked;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
@Aspect
public class MDCLoggingAspect {

    @Before("@annotation(space.br1440.platform.logging.annotation.MDCLoggable)")
    public void beforeAnnotatedMethod(JoinPoint joinPoint) {
        try {
            Method method = resolveMethod(joinPoint);
            Map<Integer, ParamMeta> paramsToLog = extractLoggableParams(method);
            populateMDC(joinPoint, paramsToLog);
        } catch (Exception e) {
            log.error("Failed to apply MDC logging for method {}: {}", joinPoint.getSignature(), e.getMessage(), e);
        }
    }

    private Method resolveMethod(JoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature methodSig = (MethodSignature) joinPoint.getSignature();
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(joinPoint.getTarget());
        return targetClass.getDeclaredMethod(methodSig.getName(), methodSig.getParameterTypes());
    }

    private Map<Integer, ParamMeta> extractLoggableParams(Method method) {
        Parameter[] parameters = method.getParameters();
        return IntStream.range(0, parameters.length)
                .mapToObj(i -> extractParamMeta(i, parameters[i]))
                .flatMap(Function.identity())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Stream<Map.Entry<Integer, ParamMeta>> extractParamMeta(int index, Parameter parameter) {
        MDCLogKey mdcAnnotation = parameter.getAnnotation(MDCLogKey.class);
        if (mdcAnnotation == null) {
            return Stream.empty();
        }

        Masked maskedAnnotation = parameter.getAnnotation(Masked.class);
        boolean isMasked = maskedAnnotation != null;
        String maskValue = isMasked ? maskedAnnotation.value() : null;

        ParamMeta meta = new ParamMeta(mdcAnnotation.value(), isMasked, maskValue);
        return Stream.of(new AbstractMap.SimpleEntry<>(index, meta));
    }

    private void populateMDC(JoinPoint joinPoint, Map<Integer, ParamMeta> paramsToLog) {
        Object[] args = joinPoint.getArgs();
        paramsToLog.forEach((index, meta) -> {
            String value = meta.masked ? meta.mask : getArgValue(args, index);
            MDC.put(meta.key, value);
        });
    }

    private String getArgValue(Object[] args, int index) {
        return Optional.ofNullable(args)
                .filter(a -> index < a.length)
                .map(a -> a[index])
                .map(String::valueOf)
                .orElse("");
    }

    private record ParamMeta(String key, boolean masked, String mask) {
    }

}
