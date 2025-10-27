package space.br1440.platform.leaderelection.schedulerlocking.configuration.converter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;

@Slf4j
public class DynamicParametersConverter {

    private final Environment environment;
    private final ApplicationContext applicationContext;
    private final ExpressionParser expressionParser;
    private final StandardEvaluationContext baseContext;

    public DynamicParametersConverter(Environment environment, ApplicationContext applicationContext) {
        this.environment = environment;
        this.applicationContext = applicationContext;
        this.expressionParser = new SpelExpressionParser();
        this.baseContext = createBaseEvaluationContext();
    }

    public DynamicLockParams resolveDynamicParams(Method method, String leaseRole,
                                                  String lockAtMostFor, String lockAtLeastFor, String keepAliveLockUntil,
                                                  Duration defaultLockAtMostFor, Duration defaultLockAtLeastFor) {
        boolean hasDynamicFeatures = isDynamicExpression(lockAtMostFor) ||
                isDynamicExpression(lockAtLeastFor) ||
                isDynamicExpression(keepAliveLockUntil);

        if (!hasDynamicFeatures) {
            return new DynamicLockParams(
                    parseDurationWithDefault(lockAtMostFor, defaultLockAtMostFor),
                    parseDurationWithDefault(lockAtLeastFor, defaultLockAtLeastFor),
                    parseDurationWithDefault(keepAliveLockUntil, Duration.ZERO),
                    false
            );
        }

        try {
            Duration resolvedLockAtMostFor = resolveDynamicDuration(lockAtMostFor, defaultLockAtMostFor, method, "lockAtMostFor", leaseRole);
            Duration resolvedLockAtLeastFor = resolveDynamicDuration(lockAtLeastFor, defaultLockAtLeastFor, method, "lockAtLeastFor", leaseRole);
            Duration resolvedKeepAliveLockUntil = resolveDynamicDuration(keepAliveLockUntil, Duration.ZERO, method, "keepAliveLockUntil", leaseRole);
            return new DynamicLockParams(resolvedLockAtMostFor, resolvedLockAtLeastFor, resolvedKeepAliveLockUntil, true);
        } catch (RuntimeException e) {
            log.error("Failed to resolve dynamic params for role '{}': {}", leaseRole, e.getMessage(), e);
            return new DynamicLockParams(
                    parseDurationWithDefault(lockAtMostFor, defaultLockAtMostFor),
                    parseDurationWithDefault(lockAtLeastFor, defaultLockAtLeastFor),
                    parseDurationWithDefault(keepAliveLockUntil, Duration.ZERO),
                    false
            );
        }
    }

    public boolean isDynamicExpression(String value) {
        return StringUtils.hasText(value) && (value.contains("${") || value.contains("#{"));
    }

    private Duration resolveDynamicDuration(String expression, Duration defaultValue, Method method,
                                            String paramName, String leaseRole) {
        if (!StringUtils.hasText(expression)) {
            return defaultValue;
        }

        if (!isDynamicExpression(expression)) {
            try {
                return StringToDurationConverter.convert(expression);
            } catch (RuntimeException e) {
                log.warn("Invalid static {} '{}' for role '{}', using default", paramName, expression, leaseRole);
                return defaultValue;
            }
        }

        String resolved = resolvePlaceholderOrSpel(expression, method);
        if (StringUtils.hasText(resolved)) {
            try {
                Duration duration = StringToDurationConverter.convert(resolved);
                if (duration.isNegative()) {
                    log.warn("Invalid dynamic {} '{}' for role '{}', using default", paramName, resolved, leaseRole);
                    return defaultValue;
                }
                return duration;
            } catch (RuntimeException e) {
                log.warn("Failed to parse dynamic {} '{}' for role '{}', using default", paramName, resolved, leaseRole);
                return defaultValue;
            }
        }

        return defaultValue;
    }

    private String resolvePlaceholderOrSpel(String expression, Method method) {
        try {
            String resolved = environment.resolvePlaceholders(expression);

            if (resolved.startsWith("#{") && resolved.endsWith("}")) {
                String spelExpression = resolved.substring(2, resolved.length() - 1);
                Expression expr = expressionParser.parseExpression(spelExpression);

                StandardEvaluationContext context = createEvaluationContext(method);
                Object result = expr.getValue(context);
                return result != null ? String.valueOf(result) : null;
            }

            return resolved;
        } catch (RuntimeException e) {
            log.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
            return null;
        }
    }

    private StandardEvaluationContext createEvaluationContext(Method method) {
        StandardEvaluationContext context = new StandardEvaluationContext(baseContext);
        context.setRootObject(method);
        return context;
    }

    private StandardEvaluationContext createBaseEvaluationContext() {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver((contextObject, beanName) -> {
            try {
                return applicationContext.getBean(beanName);
            } catch (RuntimeException e) {
                throw new NoSuchBeanDefinitionException(beanName, "Bean not found in application context for SpEL expression");
            }
        });
        return context;
    }

    private Duration parseDurationWithDefault(String durationString, Duration defaultValue) {
        return StringToDurationConverter.convertWithDefault(durationString, defaultValue);
    }

    public record DynamicLockParams(Duration lockAtMostFor,
                                    Duration lockAtLeastFor,
                                    Duration keepAliveLockUntil,
                                    boolean isDynamic) {
    }

}
