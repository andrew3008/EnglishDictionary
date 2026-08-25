package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Health contributor платформенного модуля трассировки — проверяет только fail-closed стартовый
 * контракт между настроенным intent и наблюдаемым состоянием Controlled Agent, зафиксированным на
 * старте контекста в {@link TracingStartupContractSnapshot}.
 * <p>
 * Контракт статусов:
 * <ul>
 *   <li>{@code UP} + {@code reasonCode=ENABLED_CONTRACT_SATISFIED} — runtime включён и Controlled
 *       Agent подтвердил {@code AGENT_READY} на старте контекста.</li>
 *   <li>{@code UP} + {@code reasonCode=DISABLED_CONTRACT_SATISFIED} — tracing намеренно отключён
 *       ({@code platform.tracing.enabled=false}) и Agent корректно отсутствует. Намеренное
 *       отключение телеметрии — штатное состояние деплоя: бизнес-функция приложения не деградирует,
 *       поэтому статус не понижается.</li>
 *   <li>{@code DOWN} + {@code reasonCode=STARTUP_CONTRACT_VIOLATED} — наблюдаемая комбинация
 *       {@code platformRuntimeMode}/{@code agentState} не соответствует ни одному разрешённому
 *       состоянию. Ветка не воспроизводится в штатной работе (см. fail-closed guard'ы в
 *       {@code TracingRuntimeAutoConfiguration}) и существует как страховка инварианта.</li>
 * </ul>
 * <p>
 * <b>Wiring.</b> Платформенное решение намеренно не включает этот contributor в группы
 * {@code management.endpoint.health.group.liveness}/{@code readiness}. Деградация телеметрии не
 * является основанием ни для рестарта пода, ни для снятия трафика; включение contributor'а в
 * liveness-группу создаёт риск каскадных перезапусков при сбое подсистемы наблюдаемости.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class TracingStartupContractIndicator extends AbstractHealthIndicator {

    private final TracingStartupContractSnapshot snapshot;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("tracingEnabled", snapshot.tracingEnabled());
        builder.withDetail("platformRuntimeMode", snapshot.runtimeMode());
        builder.withDetail("agentState", snapshot.agentState());

        String failureCode = snapshot.failureCode();
        if ((failureCode != null) && !failureCode.isBlank()) {
            builder.withDetail("failureCode", failureCode);
        }

        if (snapshot.outcome() == TracingStartupContractOutcome.STARTUP_CONTRACT_VIOLATED) {
            builder.down();
        } else {
            builder.up();
        }

        builder.withDetail("reasonCode", snapshot.outcome().name());
    }
}
