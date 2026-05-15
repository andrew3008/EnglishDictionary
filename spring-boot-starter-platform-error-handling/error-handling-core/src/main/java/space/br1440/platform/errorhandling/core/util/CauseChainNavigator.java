package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Утилита для навигации по цепочке причин ({@code Throwable.getCause()}).
 *
 * <h2>Гарантии</h2>
 * <ul>
 *   <li>Защита от циклических цепочек через {@code IdentityHashMap}.</li>
 *   <li>Максимальная глубина обхода ограничена {@link #DEFAULT_MAX_DEPTH}.</li>
 * </ul>
 */
@UtilityClass
public final class CauseChainNavigator {

    public static final int DEFAULT_MAX_DEPTH = 20;
    private static final int SQL_CAUSE_CHAIN_DEPTH = 5;

    @NonNull
    public static Throwable findDeepestCause(@NonNull Throwable throwable) {
        return findDeepestCause(throwable, DEFAULT_MAX_DEPTH);
    }

    @NonNull
    public static Throwable findDeepestCause(@NonNull Throwable throwable, int maxDepth) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable deepest = throwable;
        Throwable current = throwable;
        int depth = 0;

        while (current != null && depth < maxDepth && visited.add(current)) {
            deepest = current;
            current = current.getCause();
            depth++;
        }

        return deepest;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T findCause(@Nullable Throwable throwable,
                                                    @NonNull Class<T> type,
                                                    int maxDepth) {
        Throwable current = throwable;
        for (int i = 0; i < maxDepth && current != null; i++) {
            if (type.isInstance(current)) {
                return (T) current;
            }
            current = current.getCause();
        }
        return null;
    }

    @Nullable
    public static SQLException findSqlException(@Nullable Throwable throwable) {
        return findCause(throwable, SQLException.class, SQL_CAUSE_CHAIN_DEPTH);
    }
}
