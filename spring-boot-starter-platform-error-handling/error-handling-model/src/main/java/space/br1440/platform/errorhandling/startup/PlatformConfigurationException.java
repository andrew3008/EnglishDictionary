package space.br1440.platform.errorhandling.startup;

import lombok.Getter;

import java.util.Objects;

/**
 * Базовое исключение для ошибок конфигурации, обнаруживаемых при старте приложения.
 *
 * <p><b>Намеренно вне sealed-иерархии {@code AbstractErrorResponseException}</b> --
 * при старте приложения нет HTTP-контекста, нет pipeline построения {@code ErrorEntry}.
 *
 * <p><b>Расположение.</b> Класс живёт в пакете {@code errorhandling.startup/} (sibling
 * пакета {@code exception/}), а не в подпакете {@code exception/*} -- чтобы не смешивать
 * startup-диагностику с HTTP-иерархией и не требовать ArchUnit-исключений в
 * {@code ArchitectureFitnessTest}.
 *
 * <p>Поле {@link #action} содержит рекомендуемое действие для исправления ошибки
 * (человекочитаемый текст). Используется {@code PlatformConfigurationFailureAnalyzer}
 * (живёт в модуле {@code error-handling-core}) для вывода структурированного сообщения
 * в консоль при падении старта.
 *
 * <h2>Контракт для микросервисов, использующих платформенную модель Error Handling'а</h2>
 * <p>Все микросервисы могут наследовать свои классы исключений от этого класса
 * при валидации конфигурации вместо создания ad-hoc {@code RuntimeException} подклассов.
 * Это даёт:
 * <ul>
 *   <li>Единый {@code PlatformConfigurationFailureAnalyzer} для всех микросервисов</li>
 *   <li>Стандартный формат вывода ошибки в консоль</li>
 *   <li>Возможность агрегации конфиг-ошибок в мониторинге</li>
 * </ul>
 *
 * <h2>Пример</h2>
 * <pre>{@code
 * public class PatroniConfigurationConflictException extends PlatformConfigurationException {
 *     public PatroniConfigurationConflictException(String message, String action) {
 *         super(message, action);
 *     }
 * }
 *
 * throw new PatroniConfigurationConflictException(
 *         "Patroni mode enabled but no seed nodes configured",
 *         "Set 'platform.datasource.routing.patroni.nodes' to at least one Patroni REST endpoint");
 * }</pre>
 */
@Getter
public class PlatformConfigurationException extends RuntimeException {

    /**
     * Рекомендуемое действие для исправления ошибки конфигурации.
     */
    private final String action;

    public PlatformConfigurationException(String message, String action) {
        super(message);
        this.action = Objects.requireNonNull(action,"action must not be null — provide a remediation instruction for the configuration error");
    }

    public PlatformConfigurationException(String message, String action, Throwable cause) {
        super(message, cause);
        this.action = Objects.requireNonNull(action,"action must not be null — provide a remediation instruction for the configuration error");
    }
}
