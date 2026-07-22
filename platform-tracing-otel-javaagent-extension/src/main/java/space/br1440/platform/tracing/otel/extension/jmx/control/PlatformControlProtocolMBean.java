package space.br1440.platform.tracing.otel.extension.jmx.control;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecodeResult;
import space.br1440.platform.tracing.core.control.protocol.RuntimePolicyControlHandleResult;
import space.br1440.platform.tracing.core.control.protocol.RuntimePolicyControlHandler;
import space.br1440.platform.tracing.otel.extension.control.ReadAppliedStateHandler;

import javax.management.openmbean.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Production implementation of {@link PlatformControlProtocolMXBean}.
 *
 * <h2>Wire flow</h2>
 * <pre>
 * JMX caller
 *   --CompositeData--&gt;
 *     {@link #applyPolicy}  (this class: JMX-to-Map bridge)
 *       --Map&lt;String,Object&gt;--&gt;
 *         {@link TracingControlProtocol}  (api: structural validation)
 *           --DecodeResult--&gt;
 *             {@link RuntimePolicyControlHandler}  (core: domain validation + apply)
 *               --HandleResult--&gt; return status string
 * </pre>
 *
 * <h2>CompositeData → Map bridge</h2>
 * The conversion is intentionally simple: all keys present in the
 * {@code CompositeData} are copied into a {@code LinkedHashMap<String,Object>}.
 * OpenType values (numbers, booleans, strings, nested composites) are kept
 * as-is because the decoder already normalises types.
 *
 * <h2>Thread safety</h2>
 * All mutable state is confined to the holders and processors referenced
 * through the handler; this class itself is stateless beyond the injected
 * references.
 */
@Slf4j
public final class PlatformControlProtocolMBean implements PlatformControlProtocolMXBean {

    private final TracingControlProtocol          protocol;
    private final RuntimePolicyControlHandler     handler;
    private final ReadAppliedStateHandler         readHandler;
    private final LongAdder                       invalidConfigCounter;

    public PlatformControlProtocolMBean(
            TracingControlProtocol        protocol,
            RuntimePolicyControlHandler   handler,
            ReadAppliedStateHandler       readHandler,
            LongAdder                     invalidConfigCounter) {
        this.protocol            = Objects.requireNonNull(protocol,            "protocol");
        this.handler             = Objects.requireNonNull(handler,             "handler");
        this.readHandler         = Objects.requireNonNull(readHandler,         "readHandler");
        this.invalidConfigCounter = Objects.requireNonNull(invalidConfigCounter, "invalidConfigCounter");
    }

    // =========================================================================
    // MXBean operations
    // =========================================================================

    @Override
    public String applyPolicy(CompositeData payload) {
        Map<String, Object> wire = compositeToMap(payload);

        TracingControlProtocolDecodeResult decoded = protocol.decode(wire);
        RuntimePolicyControlHandleResult   result  = handler.handle(decoded);

        if (!result.isSuccess()) {
            invalidConfigCounter.increment();
            log.warn("Control-protocol apply rejected [{}]: {}",
                    result.status(), result.violations());
        } else {
            log.info("Control-protocol apply succeeded [{}]", result.status());
        }

        return formatResult(result);
    }

    @Override
    public CompositeData readAppliedState() throws OpenDataException {
        Map<String, Object> state = readHandler.read();
        return mapToCompositeData(state);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Converts a {@link CompositeData} into a flat {@code Map<String,Object>}.
     * A {@code null} input is converted to an empty map so the decoder can
     * report a proper structural violation.
     */
    private static Map<String, Object> compositeToMap(CompositeData data) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (data == null) {
            return result;
        }
        for (String key : data.getCompositeType().keySet()) {
            Object value = data.get(key);
            if (value instanceof CompositeData nested) {
                // Recursively flatten nested composites (e.g. route-ratios sub-map)
                result.put(key, compositeToMap(nested));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * Encodes a flat {@code Map<String,Object>} into a {@link CompositeData}.
     *
     * <p>Only scalar String values are included in the result type; numeric and
     * boolean values are coerced to String for maximum JMX-client compatibility.
     * This is intentionally a diagnostic/read-only representation — not a
     * round-trip wire format.
     */
    private static CompositeData mapToCompositeData(Map<String, Object> state)
            throws OpenDataException {
        // Build parallel arrays required by CompositeType / CompositeDataSupport.
        String[] keys  = state.keySet().toArray(new String[0]);
        String[] descs = new String[keys.length];
        OpenType<?>[] types = new OpenType<?>[keys.length];
        Object[]      vals  = new Object[keys.length];

        for (int i = 0; i < keys.length; i++) {
            descs[i]  = keys[i];
            types[i]  = SimpleType.STRING;
            Object v  = state.get(keys[i]);
            vals[i]   = (v == null) ? "" : v.toString();
        }

        CompositeType ct = new CompositeType(
                "AppliedState",
                "Current applied tracing runtime state",
                keys, descs, types);

        return new CompositeDataSupport(ct, keys, vals);
    }

    private static String formatResult(RuntimePolicyControlHandleResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.status().name());
        result.operation().ifPresent(op -> sb.append(' ').append(op.name()));
        if (!result.violations().isEmpty()) {
            sb.append(" violations=").append(result.violations());
        }
        return sb.toString();
    }
}
