package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * S-01 corrective evidence. Concrete, deeply immutable accepted-policy payload carrying exactly the
 * eight Category A leaves and nothing else.
 *
 * <h2>Why this type exists</h2>
 * The original S-01 evidence proved the commit mechanism with a generic payload parameter
 * {@code S}. A generic parameter cannot guarantee deep immutability, because an arbitrary {@code S}
 * may be mutable or may expose a mutable collection. The accepted-state contract therefore has to
 * name a concrete policy type, and this record is the {@code PROPOSED} shape of it.
 *
 * <h2>Leaf set</h2>
 * Exactly eight components, in the Gate E5 order:
 * <ol>
 *   <li>{@code sampling.enabled}</li>
 *   <li>{@code sampling.ratio}</li>
 *   <li>{@code sampling.route-ratios}</li>
 *   <li>{@code validation.enabled}</li>
 *   <li>{@code validation.strict}</li>
 *   <li>{@code exporter.enabled}</li>
 *   <li>{@code propagation.trace-control-headers.enabled}</li>
 *   <li>{@code diagnostics.log-level}</li>
 * </ol>
 * Category B state is absent by construction. In particular there is no scrubbing field, no
 * {@code sampling.drop-paths}, no {@code sampling.force-record-values}, no secret, and no
 * pipeline-composition field. Those are startup-only Agent configuration and are not accepted
 * runtime state.
 *
 * <h2>Deep-immutability contract</h2>
 * Seven components are primitives or a closed enum, which are immutable and safely published by
 * construction. The single reference component is the route-ratio map, and it carries five explicit
 * requirements, all asserted by {@link S01DeepImmutabilityTest} and
 * {@link S01CategoryALeafContractTest}:
 * <ul>
 *   <li><b>defensive copy on ingress</b> — the canonical constructor copies the caller's map, so the
 *       accepted policy never aliases caller-owned state;</li>
 *   <li><b>deterministic key order</b> — the copy is a natural-order {@link TreeMap}, and the source
 *       map's own ordering or comparator cannot influence it;</li>
 *   <li><b>unmodifiable representation</b> — the stored map is wrapped by
 *       {@link Collections#unmodifiableNavigableMap};</li>
 *   <li><b>no mutable backing map is returned</b> — the accessor returns the wrapper, never the
 *       {@link TreeMap}, and no other reference to the {@link TreeMap} escapes the constructor;</li>
 *   <li><b>no dependency on a mutable caller-owned map</b> — a later mutation of the source map
 *       cannot be observed through the accepted policy.</li>
 * </ul>
 * {@code Map.copyOf} is deliberately not used: it produces an unmodifiable map with unspecified
 * iteration order, and a deterministic order is required for digest stability and for a
 * reproducible wire representation. The declared component type is {@link NavigableMap} so that
 * key-sorted iteration is part of the type, not merely of the implementation.
 * <p>
 * Evidence-only. {@code PROPOSED} name; not an approved production type.
 *
 * @param samplingEnabled                        Category A leaf {@code sampling.enabled}
 * @param samplingRatio                          Category A leaf {@code sampling.ratio}, bounded to
 *                                               {@code [0.0, 1.0]}
 * @param samplingRouteRatios                    Category A leaf {@code sampling.route-ratios},
 *                                               key-sorted and unmodifiable
 * @param validationEnabled                      Category A leaf {@code validation.enabled}
 * @param validationStrict                       Category A leaf {@code validation.strict}
 * @param exporterEnabled                        Category A leaf {@code exporter.enabled}
 * @param propagationTraceControlHeadersEnabled  Category A leaf
 *                                               {@code propagation.trace-control-headers.enabled}
 * @param diagnosticsLogLevel                    Category A leaf {@code diagnostics.log-level}
 */
record S01EffectiveTracingPolicy(
        boolean samplingEnabled,
        double samplingRatio,
        NavigableMap<String, Double> samplingRouteRatios,
        boolean validationEnabled,
        boolean validationStrict,
        boolean exporterEnabled,
        boolean propagationTraceControlHeadersEnabled,
        S01LogLevel diagnosticsLogLevel) {

    S01EffectiveTracingPolicy {
        Objects.requireNonNull(samplingRouteRatios, "samplingRouteRatios");
        Objects.requireNonNull(diagnosticsLogLevel, "diagnosticsLogLevel");
        requireRatioInRange(samplingRatio, "samplingRatio");

        // Natural-order copy built here and wrapped immediately. putAll rather than the
        // TreeMap(SortedMap) constructor, because that constructor would inherit a caller-supplied
        // comparator and the key order would stop being deterministic.
        NavigableMap<String, Double> normalized = new TreeMap<>();
        for (Map.Entry<String, Double> entry : samplingRouteRatios.entrySet()) {
            String route = Objects.requireNonNull(entry.getKey(), "route key");
            Double ratio = Objects.requireNonNull(entry.getValue(), "route ratio");
            requireRatioInRange(ratio, "route ratio for " + route);
            normalized.put(route, ratio);
        }
        samplingRouteRatios = Collections.unmodifiableNavigableMap(normalized);
    }

    /**
     * Builds a policy from an arbitrary, possibly mutable, caller-owned route map.
     * <p>
     * This is the ingress the deep-immutability proof exercises: the caller keeps its map and is
     * free to mutate it afterwards.
     */
    static S01EffectiveTracingPolicy of(boolean samplingEnabled,
                                        double samplingRatio,
                                        Map<String, Double> samplingRouteRatios,
                                        boolean validationEnabled,
                                        boolean validationStrict,
                                        boolean exporterEnabled,
                                        boolean propagationTraceControlHeadersEnabled,
                                        S01LogLevel diagnosticsLogLevel) {
        NavigableMap<String, Double> sorted = new TreeMap<>();
        sorted.putAll(Objects.requireNonNull(samplingRouteRatios, "samplingRouteRatios"));
        return new S01EffectiveTracingPolicy(
                samplingEnabled,
                samplingRatio,
                sorted,
                validationEnabled,
                validationStrict,
                exporterEnabled,
                propagationTraceControlHeadersEnabled,
                diagnosticsLogLevel);
    }

    private static void requireRatioInRange(double ratio, String what) {
        if (!(ratio >= 0.0d && ratio <= 1.0d)) {
            throw new IllegalArgumentException(what + " must be within [0.0, 1.0] but was " + ratio);
        }
    }
}
