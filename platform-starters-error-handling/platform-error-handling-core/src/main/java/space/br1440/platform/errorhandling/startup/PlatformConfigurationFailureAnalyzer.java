package space.br1440.platform.errorhandling.startup;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import space.br1440.platform.errorhandling.startup.PlatformConfigurationException;

/**
 * Обобщённый {@link org.springframework.boot.diagnostics.FailureAnalyzer}
 * для всех подтипов {@link PlatformConfigurationException}.
 *
 * <p>Платформенные стартеры больше не создают индивидуальные FailureAnalyzer-ы.
 * Достаточно унаследовать исключение от {@link PlatformConfigurationException}
 * и передать в конструктор {@code message} (описание проблемы)
 * и {@code action} (рекомендуемое действие).
 *
 * <h2>Механизм обнаружения</h2>
 * <p>{@link AbstractFailureAnalyzer} использует
 * {@code findCause(rootFailure, PlatformConfigurationException.class)},
 * который разворачивает всю цепочку причин. При старте Spring Boot оборачивает
 * исключения в {@code BeanCreationException → UnsatisfiedDependencyException} --
 * {@code findCause()} корректно извлекает {@code PlatformConfigurationException} из-под этих обёрток.
 *
 * <h2>Пример вывода в консоль</h2>
 * <pre>
 * ***************************
 * APPLICATION FAILED TO START
 * ***************************
 *
 * Description:
 *
 * Patroni mode enabled but no seed nodes configured.
 *
 * Action:
 *
 * Set 'platform.datasource.routing.patroni.nodes' to at least one Patroni REST endpoint.
 * </pre>
 *
 * @see PlatformConfigurationException
 */
public class PlatformConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<PlatformConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure,
                                      PlatformConfigurationException cause) {
        return new FailureAnalysis(cause.getMessage(), cause.getAction(), cause);
    }
}
