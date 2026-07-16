package space.br1440.platform.tracing.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Маркер осознанного использования escape-hatch span-builder'ов в точке, которую обычно
 * инструментирует OTel Java Agent (контроллеры, репозитории, Kafka-листенеры, HTTP-клиенты).
 * <p>
 * <b>Назначение.</b> В agent-first модели сетевые/db/kafka span'ы создаёт Агент. Ручное создание
 * такого span'а через escape-hatch builder в agent-инструментируемом классе ведёт к двойной
 * инструментации. ArchUnit-правило запрещает такие вызовы БЕЗ этой аннотации — она документирует,
 * что автор осознаёт риск и операция Агентом НЕ покрыта.
 * <p>
 * Аннотация декларативная (runtime-эффекта нет): её наличие проверяет только ArchUnit и code review.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SuppressAgentInstrumentation {

    /** Причина: почему операция не покрыта Агентом и ручной span оправдан (для аудита). */
    String value() default "";

}
