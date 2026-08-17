package space.br1440.platform.tracing.runtime.sampling.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.runtime.sampling.engine.SamplingPolicyEngine;

class SamplingPolicyInternalContractTest {

    private static final Class<?>[] RULE_TYPES = {
            KillSwitchPolicyRule.class,
            HardDropPolicyRule.class,
            ForceHeaderPolicyRule.class,
            QaTracePolicyRule.class,
            ParentSampledPolicyRule.class,
            RouteRatioPolicyRule.class,
            DefaultRatioPolicyRule.class
    };

    @Test
    void specializedRuleTypesArePackagePrivateAndNotExposedByChainMethods() {
        assertThat(RULE_TYPES)
                .allMatch(ruleType -> !Modifier.isPublic(ruleType.getModifiers()));
        assertThat(Arrays.stream(ProductionSamplingPolicyChain.class.getMethods()))
                .noneMatch(method -> Arrays.stream(RULE_TYPES)
                        .anyMatch(ruleType -> exposes(method.getReturnType(), ruleType)
                                || Arrays.stream(method.getParameterTypes())
                                .anyMatch(type -> exposes(type, ruleType))));
    }

    @Test
    void engineAndChainCanOnlyBeCreatedThroughPlatformFactories() {
        assertThat(Arrays.stream(SamplingPolicyEngine.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(ProductionSamplingPolicyChain.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test
    void specializedRulesHaveNoServiceLoaderRegistration() {
        ClassLoader classLoader = SamplingPolicyInternalContractTest.class.getClassLoader();
        assertThat(RULE_TYPES)
                .allMatch(ruleType -> classLoader.getResource("META-INF/services/" + ruleType.getName()) == null);
    }

    private static boolean exposes(Class<?> candidate, Class<?> ruleType) {
        return candidate.equals(ruleType) || candidate.isArray() && candidate.componentType().equals(ruleType);
    }
}
