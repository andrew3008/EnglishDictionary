package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.util.UUID;

/**
 * Identity владельца cumulative-состояния: process identity ({@code agentInstanceId},
 * создаётся один раз при старте агента) плюс per-owner {@code generation}. Generation
 * принадлежит владельцу, а не атрибуту — четыре счётчика export-процессора разделяют
 * одну generation. Generation не может быть присвоена извне: единственный источник —
 * {@link OwnerRegistrationSlot#replace(Object)} и {@link OwnerRegistrationSlot#remove()}.
 */
public record JmxSourceIdentity(UUID agentInstanceId, long ownerGeneration) {

    public JmxSourceIdentity {
        if (ownerGeneration < 0) {
            throw new IllegalArgumentException("ownerGeneration должна быть неотрицательной: " + ownerGeneration);
        }
    }
}
