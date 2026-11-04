package space.br1440.platform.tracing.runtime.sampling.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.runtime.sampling.model.ParentContextState;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingOutcome;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicyRequest;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingStartupRules;

class SamplingPolicyEvaluatorTest {

    private static final String TRACE_ID = "00000000000000000000000000000001";
    private static final SamplingStartupRules RULES = SamplingStartupRules.create(List.of("/drop"), List.of("on", "custom"));

    @ParameterizedTest
    @CsvSource({
            "false,/drop,on,true,SAMPLED,1,1,KILL_SWITCH",
            "true,/drop,on,true,SAMPLED,1,1,HARD_DROP",
            "true,/drop,,true,NOT_SAMPLED,1,1,HARD_DROP",
            "true,/drop,,,SAMPLED,1,1,HARD_DROP",
            "true,/api,on,true,NOT_SAMPLED,0,0,FORCE_HEADER",
            "true,/api,on,false,SAMPLED,0,0,FORCE_HEADER",
            "true,/api,,true,NOT_SAMPLED,0,0,QA_TRACE",
            "true,/api,,true,SAMPLED,0,0,QA_TRACE",
            "true,/api,,false,SAMPLED,0,0,PARENT_SAMPLED",
            "true,/api,,false,NOT_SAMPLED,1,1,PARENT_DROP",
            "true,/api,,false,ABSENT,1,0,ROUTE_SAMPLE",
            "true,/api,,false,ABSENT,0,1,ROUTE_DROP",
            "true,/other,,false,ABSENT,0,1,DEFAULT_SAMPLE",
            "true,/other,,false,ABSENT,1,0,DEFAULT_DROP"
    })
    void firstApplicableRuleDeterminesOutcome(boolean enabled, String path, String force, Boolean qa,
                                               ParentContextState parent, double routeRatio, double defaultRatio,
                                               SamplingOutcome expected) {
        // given
        SamplingPolicyRequest request = new SamplingPolicyRequest(path, TRACE_ID, force, Boolean.TRUE.equals(qa), parent);

        // when
        SamplingOutcome outcome = SamplingPolicyEvaluator.evaluate(request, enabled, Map.of("/api", routeRatio), defaultRatio, RULES);

        // then
        assertThat(outcome).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"on", "ON", "CuStOm"})
    void forceMatchingIgnoresCase(String force) {
        // given
        SamplingPolicyRequest request = new SamplingPolicyRequest(null, TRACE_ID, force, false, ParentContextState.ABSENT);

        // when
        SamplingOutcome outcome = SamplingPolicyEvaluator.evaluate(request, true, Map.of(), 0, RULES);

        // then
        assertThat(outcome).isEqualTo(SamplingOutcome.FORCE_HEADER);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" on ", "unknown", "   "})
    void unmatchedRawForceValueFallsThrough(String force) {
        // given
        SamplingPolicyRequest request = new SamplingPolicyRequest(null, TRACE_ID, force, false, ParentContextState.ABSENT);

        // when
        SamplingOutcome outcome = SamplingPolicyEvaluator.evaluate(request, true, Map.of(), 0, RULES);

        // then
        assertThat(outcome).isEqualTo(SamplingOutcome.DEFAULT_DROP);
    }

    @Test
    void emptyStartupRulesDoNotDropOrForce() {
        // given
        SamplingPolicyRequest request = new SamplingPolicyRequest("/drop", TRACE_ID, "on", false, ParentContextState.ABSENT);

        // when
        SamplingOutcome outcome = SamplingPolicyEvaluator.evaluate(request, true, Map.of(), 0,
                SamplingStartupRules.create(List.of(), List.of()));

        // then
        assertThat(outcome).isEqualTo(SamplingOutcome.DEFAULT_DROP);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingPathSkipsDropAndRouteRules(String path) {
        // given
        SamplingPolicyRequest request = new SamplingPolicyRequest(path, TRACE_ID, null, false, ParentContextState.ABSENT);

        // when
        SamplingOutcome outcome = SamplingPolicyEvaluator.evaluate(request, true, Map.of("/", 0.0), 1, RULES);

        // then
        assertThat(outcome).isEqualTo(SamplingOutcome.DEFAULT_SAMPLE);
    }

    @ParameterizedTest
    @CsvSource({"/api/v2/orders/1,ROUTE_SAMPLE", "/api/v3,ROUTE_DROP", "/api2,ROUTE_DROP", "/API,DEFAULT_SAMPLE"})
    void longestPrefixIsIndependentOfInsertionOrder(String path, SamplingOutcome expected) {
        // given
        Map<String, Double> ascending = new LinkedHashMap<>();
        ascending.put("/api", 0.0);
        ascending.put("/api/v2", 0.0);
        ascending.put("/api/v2/orders", 1.0);
        Map<String, Double> descending = new LinkedHashMap<>();
        descending.put("/api/v2/orders", 1.0);
        descending.put("/api/v2", 0.0);
        descending.put("/api", 0.0);
        SamplingPolicyRequest request = new SamplingPolicyRequest(path, TRACE_ID, null, false, ParentContextState.ABSENT);

        // when
        SamplingOutcome first = SamplingPolicyEvaluator.evaluate(request, true, ascending, 1, RULES);
        SamplingOutcome second = SamplingPolicyEvaluator.evaluate(request, true, descending, 1, RULES);

        // then
        assertThat(first).isEqualTo(expected);
        assertThat(second).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0000000000000001,ROUTE_SAMPLE", "7fffffffffffffff,ROUTE_DROP"})
    void fractionalRouteRatioIsTerminal(String lowerBits, SamplingOutcome expected) {
        // given
        SamplingPolicyRequest request = new SamplingPolicyRequest("/api", "0000000000000000" + lowerBits,
                null, false, ParentContextState.ABSENT);

        // when
        SamplingOutcome outcome = SamplingPolicyEvaluator.evaluate(request, true, Map.of("/api", 0.5), 1, RULES);

        // then
        assertThat(outcome).isEqualTo(expected);
    }
}
