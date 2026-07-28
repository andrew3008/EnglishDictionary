package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Objects;

/**
 * S-01 spike type. Optimistic-concurrency base that a caller claims to have observed before
 * building a candidate policy.
 * <p>
 * Evidence-only: this type exists to prove Gate E3 semantics executably. It is not an approved
 * production name and must not be referenced from production source sets.
 *
 * @param runtimeInstanceId identity of the agent runtime that produced the observed base
 * @param acceptedSequence  accepted sequence observed together with {@code runtimeInstanceId}
 */
record S01ExpectedBase(String runtimeInstanceId, long acceptedSequence) {

    S01ExpectedBase {
        Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        if (acceptedSequence < 0) {
            throw new IllegalArgumentException("acceptedSequence must not be negative");
        }
    }
}
