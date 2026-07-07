package space.br1440.platform.tracing.core.sampling.config;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyPropertiesValidator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Доменная fail-fast матрица {@link SamplingPolicyPropertiesValidator}.
 * <p>
 * Дополняет lenient-семантику фабрики: одни и те же невалидные данные фабрика молча пропускает,
 * а валидатор отклоняет. Сообщения совпадают с доменной частью SamplerPolicyUpdate.validateDomain,
 * чтобы переподключение в P2 не меняло наблюдаемые ошибки.
 */
class SamplingPolicyPropertiesValidatorTest {

    private static SamplingPolicyProperties valid() {
        return new SamplingPolicyProperties(true, 0.5, List.of("/m"), Set.of("on"), Map.of("/api", 0.5));
    }

    private static SamplingPolicyProperties with(double defaultRatio, List<String> drops,
                                                 Set<String> force, Map<String, Double> routes) {
        return new SamplingPolicyProperties(true, defaultRatio, drops, force, routes);
    }

    @Test
    void acceptsValidConfig() {
        assertThatCode(() -> SamplingPolicyPropertiesValidator.validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void acceptsBoundaryRatios() {
        assertThatCode(() -> SamplingPolicyPropertiesValidator.validate(
                with(0.0, List.of(), Set.of(), Map.of("/api", 1.0)))).doesNotThrowAnyException();
        assertThatCode(() -> SamplingPolicyPropertiesValidator.validate(
                with(1.0, List.of(), Set.of(), Map.of("/api", 0.0)))).doesNotThrowAnyException();
    }

    @Test
    void validateDefaultRatio_acceptsBoundaryValues() {
        assertThatCode(() -> SamplingPolicyPropertiesValidator.validateDefaultRatio(0.0))
                .doesNotThrowAnyException();
        assertThatCode(() -> SamplingPolicyPropertiesValidator.validateDefaultRatio(1.0))
                .doesNotThrowAnyException();
    }

    @Test
    void validateDefaultRatio_rejectsBelowZero() {
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validateDefaultRatio(-0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("defaultRatio must be in [0.0, 1.0]");
    }

    @Test
    void validateDefaultRatio_rejectsAboveOne() {
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validateDefaultRatio(1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("defaultRatio must be in [0.0, 1.0]");
    }

    @Test
    void rejectsOutOfRangeDefaultRatio() {
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(
                with(1.5, List.of(), Set.of(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultRatio");
    }

    @Test
    void rejectsNullDropPath() {
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(
                with(0.5, java.util.Collections.singletonList(null), Set.of(), Map.of())))
                .hasMessageContaining("Drop path");
    }

    @Test
    void rejectsBadDropPathFormat() {
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(
                with(0.5, List.of("noslash"), Set.of(), Map.of())))
                .hasMessageContaining("Drop path must start with");
    }

    @Test
    void rejectsNullForceValue() {
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(
                with(0.5, List.of(), java.util.Collections.singleton(null), Map.of())))
                .hasMessageContaining("Force record value");
    }

    @Test
    void rejectsBlankRoutePrefix() {
        Map<String, Double> routes = new LinkedHashMap<>();
        routes.put("   ", 0.5);
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(
                with(0.5, List.of(), Set.of(), routes)))
                .hasMessageContaining("Route ratio prefix");
    }

    @Test
    void rejectsOutOfRangeRouteRatio() {
        assertThatThrownBy(() -> SamplingPolicyPropertiesValidator.validate(
                with(0.5, List.of(), Set.of(), Map.of("/api", 1.5))))
                .hasMessageContaining("Route ratio");
    }
}
