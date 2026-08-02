package space.br1440.platform.tracing.autoconfigure.diagnostics.health;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;

/**
 * Health contributor платформенного модуля трассировки.
 * <p>
 * Контракт статусов:
 * <ul>
 *   <li>{@code UP} + {@code reasonCode=OPERATIONAL} — runtime включён и Controlled Agent
 *       подтвердил {@code AGENT_READY} на старте контекста.</li>
 *   <li>{@code UP} + {@code reasonCode=CONFIGURED_DISABLED} — tracing намеренно отключён
 *       ({@code platform.tracing.enabled=false}) и Agent корректно отсутствует. Намеренное
 *       отключение телеметрии — штатное состояние деплоя: бизнес-функция приложения не
 *       деградирует, поэтому статус не понижается.</li>
 *   <li>{@code DOWN} + {@code reasonCode=STARTUP_CONTRACT_VIOLATED} — наблюдаемая комбинация
 *       {@code platformRuntimeMode}/{@code agentState} не соответствует ни одному разрешённому
 *       состоянию (см. ниже).</li>
 * </ul>
 * <p>
 * Индикатор читает только неизменяемый startup-снимок {@link TracingDeploymentDiagnostics} и
 * состояние {@link TracingRuntime#state()}: он не опрашивает Agent, не обращается к Collector,
 * не выполняет блокирующих операций и не влияет на производительность приложения.
 * <p>
 * <b>Достижимые состояния.</b> Стартовый контракт fail-closed (проверка ожидания
 * {@code AgentDeploymentExpectation} и последующая проверка при создании {@code TracingRuntime})
 * не даёт контексту подняться ни в одной комбинации, кроме
 * {@code ENABLED}/{@code AGENT_READY} и {@code DISABLED_BY_CONFIGURATION}/{@code DISABLED}.
 * Ветка {@code DOWN} поэтому не воспроизводится в штатной работе и существует как страховка
 * инварианта: если стартовый контракт будет ослаблен или обойдён, приложение, считающее себя
 * инструментированным без готового Agent, обязано сообщить об этом явно, а не молча вернуть
 * {@code UP}.
 * <p>
 * <b>Wiring.</b> Репозиторий намеренно не включает contributor {@code tracing} в группы
 * {@code management.endpoint.health.group.liveness}/{@code readiness}. Деградация телеметрии не
 * является основанием ни для рестарта пода, ни для снятия трафика; включение contributor в
 * liveness-группу создаёт риск каскадных перезапусков при сбое подсистемы наблюдаемости.
 */
@RequiredArgsConstructor
public final class TracingHealthIndicator extends AbstractHealthIndicator {

    private static final String REASON_OPERATIONAL = "OPERATIONAL";
    private static final String REASON_CONFIGURED_DISABLED = "CONFIGURED_DISABLED";
    private static final String REASON_STARTUP_CONTRACT_VIOLATED = "STARTUP_CONTRACT_VIOLATED";

    private final TracingRuntime tracingRuntime;
    private final TracingDeploymentDiagnostics diagnostics;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        TracingRuntimeMode runtimeMode = tracingRuntime.state().mode();
        AgentRuntimeState agentState = diagnostics.runtimeState();

        builder.withDetail("tracingEnabled", diagnostics.tracingEnabled());
        builder.withDetail("platformRuntimeMode", runtimeMode.name());
        builder.withDetail("agentState", agentState.name());

        String failureCode = diagnostics.extensionDescriptor().failureCode();
        if (!failureCode.isBlank()) {
            builder.withDetail("failureCode", failureCode);
        }

        if (runtimeMode == TracingRuntimeMode.ENABLED && agentState == AgentRuntimeState.AGENT_READY) {
            builder.up().withDetail("reasonCode", REASON_OPERATIONAL);
            return;
        }

        if (runtimeMode == TracingRuntimeMode.DISABLED_BY_CONFIGURATION && agentState == AgentRuntimeState.DISABLED) {
            builder.up().withDetail("reasonCode", REASON_CONFIGURED_DISABLED);
            return;
        }

        builder.down().withDetail("reasonCode", REASON_STARTUP_CONTRACT_VIOLATED);
    }
}
