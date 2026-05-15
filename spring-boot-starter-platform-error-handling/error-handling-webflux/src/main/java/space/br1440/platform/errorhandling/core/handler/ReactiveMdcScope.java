package space.br1440.platform.errorhandling.core.handler;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import reactor.util.context.ContextView;

/**
 * Безопасный мост между Reactor {@link ContextView} и SLF4J MDC (ThreadLocal)
 * для синхронных секций реактивного pipeline.
 *
 * <h2>Назначение</h2>
 * <p>В реактивных приложениях прямой вызов {@code MDC.put(...)} опасен:
 * Reactor pipeline может перепланироваться на любой поток между операторами,
 * из-за чего ThreadLocal-значения «протекают» в чужие requests или, наоборот,
 * исчезают в момент логирования. Это приводит к повреждению логов и нарушению корреляции (CWE-200, CWE-117).
 *
 * <p>Правильная модель — хранить контекст исключительно в Reactor
 * {@link reactor.util.context.Context}, а в ThreadLocal восстанавливать его
 * только на короткое окно вокруг синхронной операции (например, вызов {@code log.warn(...)}).
 * Этот хелпер инкапсулирует такую модель через Micrometer Context Propagation API.
 */
@UtilityClass
public final class ReactiveMdcScope {

    private static final ContextSnapshotFactory SNAPSHOT_FACTORY = ContextSnapshotFactory.builder()
            .contextRegistry(ContextRegistry.getInstance())
            .build();

    @NonNull
    public static ContextSnapshot.Scope open(@Nullable ContextView contextView) {
        if (contextView == null) {
            return SNAPSHOT_FACTORY.captureAll().setThreadLocals();
        }

        return SNAPSHOT_FACTORY.captureFrom(contextView).setThreadLocals();
    }
}
