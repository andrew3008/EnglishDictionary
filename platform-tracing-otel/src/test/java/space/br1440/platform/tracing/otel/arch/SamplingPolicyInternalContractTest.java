package space.br1440.platform.tracing.otel.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.sampling.engine.SamplingPolicyEngine;
import space.br1440.platform.tracing.otel.sampling.policy.ProductionSamplingPolicyChain;

class SamplingPolicyInternalContractTest {

    private static final String RULE_TYPE =
            "space.br1440.platform.tracing.otel.sampling.policy.SamplingPolicyRule";

    @Test
    void samplingPolicyRuleIsPackagePrivateAndNotExposedByChainMethods() throws ClassNotFoundException {
        Class<?> ruleType = Class.forName(RULE_TYPE);

        assertThat(ruleType.isInterface()).isTrue();
        assertThat(Modifier.isPublic(ruleType.getModifiers())).isFalse();
        assertThat(Arrays.stream(ProductionSamplingPolicyChain.class.getMethods()))
                .noneMatch(method -> exposes(method.getReturnType(), ruleType)
                        || Arrays.stream(method.getParameterTypes()).anyMatch(type -> exposes(type, ruleType)));
    }

    @Test
    void engineAndChainCanOnlyBeCreatedThroughPlatformFactories() {
        assertThat(Arrays.stream(SamplingPolicyEngine.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(ProductionSamplingPolicyChain.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test
    void samplingRuleHasNoServiceLoaderRegistration() {
        String descriptor = "META-INF/services/" + RULE_TYPE;

        assertThat(SamplingPolicyInternalContractTest.class.getClassLoader().getResource(descriptor)).isNull();
    }

    private static boolean exposes(Class<?> candidate, Class<?> ruleType) {
        return candidate.equals(ruleType) || candidate.isArray() && candidate.componentType().equals(ruleType);
    }
}
