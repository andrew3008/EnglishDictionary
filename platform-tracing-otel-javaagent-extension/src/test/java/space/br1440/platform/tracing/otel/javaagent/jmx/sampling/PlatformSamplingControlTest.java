package space.br1440.platform.tracing.otel.javaagent.jmx.sampling;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyView;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PlatformSamplingControl} (sampling domain MBean).
 */
class PlatformSamplingControlTest {

    @Test
    void getSamplingRatio_возвращает_текущее_значение_sampler() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.42d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, new LongAdder());

        assertThat(control.getSamplingRatio()).isEqualTo(0.42d);
    }

    @Test
    void setSamplingRatio_передаёт_значение_в_sampler() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.1d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, new LongAdder());

        control.setSamplingRatio(0.7d);

        assertThat(holder.current().samplingRatio()).isEqualTo(0.7d);
    }

    @Test
    void setSamplingRatio_невалидное_значение_бросает_IllegalArgumentException() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.1d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, new LongAdder());

        assertThatThrownBy(() -> control.setSamplingRatio(2.0d))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateSamplingPolicy_атомарно_меняет_все_поля_за_один_bump_версии() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.1d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, new LongAdder());
        long v0 = holder.current().acceptedSequence();

        control.updateSamplingPolicy(false, 0.5d, Map.of("/api", 0.2d),
                new String[]{"/actuator/health"}, new String[]{"on"});

        AcceptedPolicyView accepted = holder.current();
        assertThat(accepted.samplingEnabled()).isFalse();
        assertThat(accepted.samplingRatio()).isEqualTo(0.5d);
        assertThat(accepted.samplingRouteRatios()).containsEntry("/api", 0.2d);
        assertThat(holder.currentLegacySamplingRules().getDroppedRoutes())
                .contains("/actuator/health");
        assertThat(holder.currentLegacySamplingRules().getForceRecordValues()).contains("on");
        assertThat(accepted.acceptedSequence()).isEqualTo(v0 + 1);
    }

    @Test
    void updateSamplingPolicy_невалидный_ratio_бросает_IAE_и_сохраняет_last_known_good() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.3d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, new LongAdder());
        long v0 = holder.current().acceptedSequence();

        assertThatThrownBy(() -> control.updateSamplingPolicy(true, 2.0d, Map.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(holder.current().samplingRatio()).isEqualTo(0.3d);
        assertThat(holder.current().acceptedSequence()).isEqualTo(v0);
    }

    @Test
    void updateSamplingPolicy_невалидный_route_ratio_бросает_IAE_и_сохраняет_last_known_good() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of("on"), Map.of("/api", 0.2d), 0.3d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, new LongAdder());
        long v0 = holder.current().acceptedSequence();

        assertThatThrownBy(() -> control.updateSamplingPolicy(
                true, 0.5d, Map.of("/api", 5.0d), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Route ratio");

        assertThat(holder.current().samplingRatio()).isEqualTo(0.3d);
        assertThat(holder.current().samplingRouteRatios()).containsEntry("/api", 0.2d);
        assertThat(holder.current().acceptedSequence()).isEqualTo(v0);
    }

    @Test
    void setRouteRatios_невалидное_значение_бросает_IAE_и_сохраняет_last_known_good() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of("on"), Map.of("/api", 0.2d), 0.3d);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, new LongAdder());
        long v0 = holder.current().acceptedSequence();

        assertThatThrownBy(() -> control.setRouteRatios(Map.of("/api", -0.1d)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(holder.current().samplingRouteRatios()).containsEntry("/api", 0.2d);
        assertThat(holder.current().acceptedSequence()).isEqualTo(v0);
    }

    @Test
    void nullConfigHolder_геттеры_возвращают_дефолты() {
        PlatformSamplingControl control = new PlatformSamplingControl(null, null, new LongAdder());

        assertThat(control.getSamplingRatio()).isEqualTo(-1.0);
        assertThat(control.isSamplerEnabled()).isFalse();
        assertThat(control.getRouteRatios()).isEmpty();
        assertThat(control.getDropPathPrefixes()).isEmpty();
        assertThat(control.getSamplingConfigVersion()).isEqualTo(-1);
    }

    @Test
    void invalidConfigCounter_инкрементируется_при_ошибке() {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of("on"), Map.of(), 0.5);
        LongAdder counter = new LongAdder();
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, counter);

        assertThatThrownBy(() -> control.setSamplingRatio(-0.1d))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(counter.sum()).isEqualTo(1);
    }
}
