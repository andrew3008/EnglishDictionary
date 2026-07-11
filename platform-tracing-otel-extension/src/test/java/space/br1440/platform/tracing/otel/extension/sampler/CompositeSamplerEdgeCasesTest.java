package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.util.List;

class CompositeSamplerEdgeCasesTest {

    private static Context withControl(boolean force, boolean qa) {
        InboundTraceControl control = new InboundTraceControl(force, qa, null, force ? "x_trace_on" : (qa ? "qa_trace" : null), force ? "on" : null);
        return Context.root().with(PlatformTraceContextKeys.TRACE_CONTROL, control);
    }
    
    private static Context withControlRaw(String raw) {
        boolean force = "on".equalsIgnoreCase(raw);
        InboundTraceControl control = new InboundTraceControl(force, false, null, force ? "x_trace_on" : null, raw);
        return Context.root().with(PlatformTraceContextKeys.TRACE_CONTROL, control);
    }

    @Test
    void не_падает_на_пустом_списке_значений() {
        SamplerStateHolder holder = new SamplerStateHolder(true, List.of(), List.of(), java.util.Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .parentContext(withControlRaw("on"))
                        .sample())
                .isDrop();
    }

    @Test
    void пустой_префикс_в_конфиге_не_отбрасывает_все_пути() {
        SamplerStateHolder holder = new SamplerStateHolder(true, List.of("", "   ", "/actuator/health"), List.of("on"), java.util.Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .putStringAttribute("url.path", "/api/v1/orders")
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void null_url_path_не_вызывает_npe() {
        SamplerStateHolder holder = new SamplerStateHolder(true, List.of("/actuator"), List.of("on"), java.util.Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        // без url.path атрибута
                        .sample())
                .isRecordAndSample();
    }

    @Test
    void kill_switch_отменяет_даже_force_и_qa() {
        SamplerStateHolder holder = new SamplerStateHolder(false, List.of(), List.of("on"), java.util.Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .parentContext(withControl(true, true))
                        .sample())
                .isDrop();
    }

    @Test
    void форсирование_не_отменяет_drop_paths() {
        SamplerStateHolder holder = new SamplerStateHolder(true, List.of("/actuator"), List.of("on"), java.util.Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .parentContext(withControl(true, false))
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }

    @Test
    void qa_сигнал_не_отменяет_drop_paths() {
        SamplerStateHolder holder = new SamplerStateHolder(true, List.of("/actuator"), List.of("on"), java.util.Map.of(), 0.0);
        CompositeSampler sampler = new CompositeSampler(holder);

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .parentContext(withControl(false, true))
                        .putStringAttribute("url.path", "/actuator/health")
                        .sample())
                .isDrop();
    }
}
