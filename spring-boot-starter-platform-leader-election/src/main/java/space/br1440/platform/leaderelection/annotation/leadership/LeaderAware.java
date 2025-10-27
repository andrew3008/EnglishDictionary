package space.br1440.platform.leaderelection.annotation.leadership;

import java.lang.annotation.*;

/**
 * Аннотация для полей или методов сеттеров, в которые автоматически внедряется информация о статусе лидера.
 * <p>
 * <strong>Синхронное и асинхронное поведение:</strong>
 * <ul>
 *   <li><strong>Поля типа boolean/Boolean:</strong> работают синхронно - значение обновляется мгновенно при изменении лидерства</li>
 *   <li><strong>Поля типа LeaderInfo:</strong> работают асинхронно - создается AsyncLeaderInfoProxy с периодическим обновлением</li>
 *   <li><strong>Методы:</strong> вызываются синхронно при изменении статуса лидерства</li>
 * </ul>
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LeaderAware {

    /**
     * Период автообновления информации о лидерстве в миллисекундах.
     * <p>
     * <strong>Применение:</strong>
     * <ul>
     *   <li>Используется только для полей типа LeaderInfo</li>
     *   <li>Для полей boolean/Boolean и методов игнорируется</li>
     * </ul>
     * <p>
     * <strong>Значения:</strong>
     * <ul>
     *   <li>0 - автообновление отключено (только инициализация при создании)</li>
     *   <li>&gt; 0 - периодическое обновление с указанным интервалом</li>
     * </ul>
     */
    long ttlMillis() default 0;

}
