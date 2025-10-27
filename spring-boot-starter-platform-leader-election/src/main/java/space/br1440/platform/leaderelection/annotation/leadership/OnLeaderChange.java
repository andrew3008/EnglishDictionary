package space.br1440.platform.leaderelection.annotation.leadership;

import java.lang.annotation.*;

/**
 * Аннотация для методов, которые должны выполняться при изменении статуса лидерства POD'а.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnLeaderChange {

	/**
	 * Определяет, будет ли задача выполняться асинхронно.
	 * Если true, метод будет обернут в CompletableFuture и выполнен в отдельном потоке.
	 */
	boolean async() default false;

	/**
	 * Тип события изменения лидерства, при котором должен быть выполнен метод.
	 * Может быть {@link ChangeEvent#BECOME_LEADER}, {@link ChangeEvent#LOST_LEADERSHIP} или {@link ChangeEvent#BOTH}.
	 */
	ChangeEvent on() default ChangeEvent.BECOME_LEADER;

	/**
	 * Определяет тип события изменения лидерства.
	 */
	enum ChangeEvent {
		/** Выполнить метод, когда POD становится лидером. */
		BECOME_LEADER,
		/** Выполнить метод, когда POD теряет лидерство. */
		LOST_LEADERSHIP,
		/** Выполнить метод при любом изменении статуса лидерства (получение или потеря). */
		BOTH
	}

}
