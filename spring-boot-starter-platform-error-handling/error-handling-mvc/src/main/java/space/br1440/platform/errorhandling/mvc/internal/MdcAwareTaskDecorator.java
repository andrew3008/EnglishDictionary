package space.br1440.platform.errorhandling.mvc.internal;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;

import java.util.Map;

/**
 * {@link TaskDecorator}, переносящий MDC из вызывающего потока в дочерний.
 *
 * <p>Java 21 + {@code spring.threads.virtual.enabled=true}: каждый
 * {@code @Async}/{@code Executor.execute(...)} создаёт НОВЫЙ virtual thread,
 * у которого свой ThreadLocal-подобный контекст MDC. Без этого декоратора
 * {@code correlationId}, выставленный в {@link CorrelationIdFilter}, теряется
 * на первой же async-границе — и все логи внутри async-операций становятся
 * без correlationId, ломая корреляцию по ELK/Datadog.
 *
 * <h2>Регистрация</h2>
 * <p>Применяется к любому {@code ThreadPoolTaskExecutor} / {@code AsyncConfigurer}
 * через {@code executor.setTaskDecorator(this)}. Не регистрируется автоматически:
 * стартер не знает, какие executor'ы у пользователя — их может быть несколько.
 * Bean экспонируется и пользователь подключает его явно через {@code AsyncConfigurer}
 * или {@code @Bean}-override таска-executor'а.
 */
public final class MdcAwareTaskDecorator implements TaskDecorator {

    @NonNull
    @Override
    public Runnable decorate(@NonNull Runnable runnable) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (snapshot != null) {
                    MDC.setContextMap(snapshot);
                } else {
                    MDC.clear();
                }

                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
