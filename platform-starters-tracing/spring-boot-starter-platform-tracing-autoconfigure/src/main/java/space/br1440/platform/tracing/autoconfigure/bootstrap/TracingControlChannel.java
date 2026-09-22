package space.br1440.platform.tracing.autoconfigure.bootstrap;

public enum TracingControlChannel {

    /**
     * Write-клиент не создаётся. Sampling и validation level — только agent bootstrap-свойства.
     * {@code exporter.enabled}, {@code propagation.trace-control-headers.enabled}, {@code diagnostics.log-level}
     * остаются на seed-значении ({@code true}/{@code true}/{@code INFO}) до рестарта — осознанное ограничение.
     */
    STATIC,

    /**
     * Полный манифест {@code platform.tracing.policy.*} из Spring Cloud Config Server.
     * Принимаются только authoritative property sources; локальные override отклоняются.
     * Refresh через {@code EnvironmentChangeEvent}. Неполный манифест на refresh не применяется:
     * агент продолжает работать на последней успешно применённой политике, библиотечные default'ы не подставляются.
     * Initial delivery обязана быть {@code APPLIED}/{@code UNCHANGED}, иначе fail-start.
     */
    CONFIG_SERVER,

    /**
     * Изменение одного параметра политики через {@code POST /management/tracingControl/{group}/{setting}}.
     * Читает текущую применённую политику агента, заменяет в ней одно поле и отправляет её целиком
     * с проверкой, что версия на агенте за это время не изменилась.
     * Не имеет собственной авторизации — защита на стороне Actuator security. Для dev/pre-prod.
     */
    ACTUATOR

}
