package space.br1440.platform.tracing.autoconfigure.control.policy.read;

import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;

public record AcceptedSnapshotWithSequence(long acceptedSequence,
                                           String runtimeInstanceId,
                                           CompleteTracingPolicySnapshot snapshot) {

    /**
     * Атомарная пара (runtimeInstanceId, acceptedSequence), наблюдённая вместе с этим
     * снапшотом за один и тот же read. Единственный корректный источник expected base
     * для последующего {@code applySnapshot} — использование отдельно взятых
     * {@link #runtimeInstanceId()} и {@link #acceptedSequence()} из разных чтений
     * запрещено вызывающим кодом.
     */
    public ExpectedTracingPolicyBase expectedBase() {
        return new ExpectedTracingPolicyBase(runtimeInstanceId, acceptedSequence);
    }
}
