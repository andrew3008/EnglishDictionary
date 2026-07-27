package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Map;
import java.util.Objects;

/**
 * S-01 spike type. Immutable policy payload used as the {@code S} type parameter in the proofs.
 * <p>
 * This is <b>not</b> the Category A schema. The Category A leaf set is fixed by Gate E5 and is out
 * of scope for S-01. This record exists only to give the publication proof something with a
 * checkable cross-field invariant: every field is derived from {@link #seed()}, so a reader that
 * observes a partially constructed instance (default {@code false}/{@code 0.0}/{@code null}) can
 * detect it via {@link #selfConsistent()}.
 * <p>
 * The mix of primitive and reference fields is deliberate: unsafe publication of a reference field
 * surfaces as an observed {@code null} map, which no legal fully constructed instance can have.
 *
 * @param seed        value every other field is derived from
 * @param enabled     derived flag
 * @param ratio       derived ratio
 * @param mode        derived reference field
 * @param routeRatios derived reference field
 */
record S01Policy(long seed, boolean enabled, double ratio, String mode, Map<String, Double> routeRatios) {

    S01Policy {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(routeRatios, "routeRatios");
    }

    /** Builds the only legal fully derived instance for {@code seed}. */
    static S01Policy of(long seed) {
        return new S01Policy(
                seed,
                (seed & 1L) == 1L,
                (seed % 1000L) / 1000.0d,
                "mode-" + seed,
                Map.of("/route/" + seed, (seed % 1000L) / 1000.0d));
    }

    /**
     * True only when every field matches the value derived from {@link #seed()}.
     * <p>
     * Allocation-free: no boxing, no intermediate collection, no string concatenation on the
     * checked path except the two reference comparisons, which use {@code equals} against values
     * already held by the observed instance.
     */
    boolean selfConsistent() {
        return enabled == ((seed & 1L) == 1L)
                && ratio == (seed % 1000L) / 1000.0d
                && mode.equals("mode-" + seed)
                && routeRatios.size() == 1
                && routeRatios.containsKey("/route/" + seed);
    }
}
