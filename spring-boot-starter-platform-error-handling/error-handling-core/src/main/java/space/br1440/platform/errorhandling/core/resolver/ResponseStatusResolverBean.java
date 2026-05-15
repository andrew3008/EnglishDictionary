package space.br1440.platform.errorhandling.core.resolver;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.HttpStatusResolution;
import space.br1440.platform.errorhandling.extension.ResponseStatusContributor;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spring-бин-оркестратор: обходит цепочку причин исключения и на каждом уровне опрашивает
 * зарегистрированные реализации {@link ResponseStatusContributor}.
 *
 * <h3>Алгоритм обхода</h3>
 * <ol>
 *   <li>Для каждого узла цепочки (от {@code throwable} вниз до глубины {@code maxDepth}):
 *     <ol type="a">
 *       <li>Опросить каждый {@link ResponseStatusContributor} в порядке возрастания
 *           {@link org.springframework.core.Ordered}.</li>
 *       <li>Вернуть первый непустой {@link Optional}.</li>
 *     </ol>
 *   </li>
 *   <li>Если ни один contributor не сработал ни на одном узле — {@link Optional#empty()}.</li>
 * </ol>
 */
public class ResponseStatusResolverBean {

    private final List<ResponseStatusContributor> contributors;

    public ResponseStatusResolverBean(@NonNull List<ResponseStatusContributor> contributors) {
        this.contributors = contributors.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
    }

    @NonNull
    public Optional<HttpStatusResolution> resolve(@NonNull Throwable throwable, int maxDepth) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        Throwable current = throwable;
        int effectiveDepth = Math.max(0, maxDepth);
        for (int depth = 0; depth <= effectiveDepth && current != null; depth++) {
            if (!visited.add(current)) {
                break;
            }

            for (ResponseStatusContributor contributor : contributors) {
                Optional<HttpStatusResolution> result = contributor.resolve(current);
                if (result.isPresent()) {
                    return result;
                }
            }

            current = current.getCause();
        }

        return Optional.empty();
    }
}
