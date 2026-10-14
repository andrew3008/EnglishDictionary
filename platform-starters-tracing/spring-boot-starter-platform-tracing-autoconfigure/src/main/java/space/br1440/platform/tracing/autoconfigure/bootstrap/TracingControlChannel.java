package space.br1440.platform.tracing.autoconfigure.bootstrap;

public enum TracingControlChannel {

    /**
     * После agent bootstrap принятая политика остаётся неизменной до рестарта.
     * Spring подключает только read-path для статуса и не создаёт write-компоненты.
     */
    NONE,

    /**
     * Полный манифест {@code platform.tracing.policy.*} из Spring Cloud Config Server.
     * Принимаются только authoritative property sources; локальные override отклоняются.
     * Refresh через {@code EnvironmentChangeEvent}. Неполный манифест на refresh не применяется:
     * агент продолжает работать на последней успешно применённой политике, библиотечные default'ы не подставляются.
     * Initial delivery обязана быть {@code APPLIED}/{@code UNCHANGED}, иначе fail-start.
     */
    CONFIG_SERVER,

    /**
     * Изменение одного параметра политики через POST-операции Actuator endpoint {@code tracingControl}.
     * Читает текущую применённую политику агента, заменяет в ней одно поле и отправляет её целиком
     * с проверкой, что версия на агенте за это время не изменилась.
     * Не имеет собственной авторизации — защита на стороне Actuator security. Для dev/pre-prod.
     */
    ACTUATOR

}
