package space.br1440.platform.tracing.jmx.contract.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TracingSettingsDigestStabilityTest {

    @Test
    void digestIsStableAcrossRendererRefactor() throws Exception {
        Class<?> generated = compileAndLoadDigestClass();
        Map<String, String> expected = expectedDigests();
        Map<String, String> actuals = new LinkedHashMap<>();
        for (Map.Entry<String, DigestCase> entry : cases().entrySet()) {
            actuals.put(entry.getKey(), invokeSha256(generated, entry.getValue()));
        }
        assertThat(expected).as("baseline digest literals must be captured").isEqualTo(actuals);
    }

    private static Map<String, String> expectedDigests() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("all-false-boundary-zero", "fef637dad839644f2181beae7d85d1de2de5f496389e8ef599e643dd9657d2f9");
        result.put("all-true-boundary-one-with-routes", "eda630a6f5026446ab316b3514557ce9d464403770ba00a7a0f93c1eec84563d");
        result.put("mixed-negative-zero-route", "a69715fbc8e3898feae41add1f6139d6c54e95e227aea7d7790d263a83b48857");
        return result;
    }

    private static Map<String, DigestCase> cases() {
        Map<String, DigestCase> result = new LinkedHashMap<>();
        result.put("all-false-boundary-zero", new DigestCase(
                false, 0.0, Map.of(), "DISABLED", false, false, "OFF"
        ));

        result.put("all-true-boundary-one-with-routes", new DigestCase(
                true, 1.0, Map.of("/b", 0.5, "/a", 0.25), "ERROR", true, true, "TRACE"
        ));

        result.put("mixed-negative-zero-route", new DigestCase(
                true, 0.5, Map.of("/route", -0.0), "WARNING", false, true, "DEBUG"
        ));

        return result;
    }

    private record DigestCase(boolean samplingEnabled,
                              double samplingRatio,
                              Map<String, Double> samplingRouteRatios,
                              String validationLevel,
                              boolean exporterEnabled,
                              boolean traceControlHeadersEnabled,
                              String diagnosticsLogLevel) {
    }

    private static String invokeSha256(Class<?> generated, DigestCase digestCase) throws Exception {
        Method method = generated.getMethod(
                "sha256", boolean.class, double.class, Map.class,
                String.class, boolean.class, boolean.class, String.class
        );

        try {
            return (String) method.invoke(
                    null,
                    digestCase.samplingEnabled(),
                    digestCase.samplingRatio(),
                    digestCase.samplingRouteRatios(),
                    digestCase.validationLevel(),
                    digestCase.exporterEnabled(),
                    digestCase.traceControlHeadersEnabled(),
                    digestCase.diagnosticsLogLevel()
            );
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("sha256(...) invocation failed", exception.getCause());
        }
    }

    private static Class<?> compileAndLoadDigestClass() throws Exception {
        String packageName = "space.br1440.platform.tracing.jmx.contract.generated.digeststability";
        ContractDocument contract = contractWithFixturePolicyDigest();
        return GeneratedContractSourceCompiler.compileAndLoad(
                "digest-stability-",
                "renderDigest",
                new Class<?>[]{ContractDocument.class, String.class},
                new Object[]{contract, packageName},
                packageName,
                "JmxTracingSettingsDigest"
        );
    }

    private static ContractDocument contractWithFixturePolicyDigest() {
        Map<String, Object> policyDigest = Map.of(
                "domainSeparator", "platform-tracing/policy-digest/v1",
                "encodingVersion", 1
        );

        Map<String, Object> wireGraph = Map.of(
                "maxRouteEntries", 64,
                "maxRoutePrefixUtf8Bytes", 256
        );

        Map<String, Object> root = Map.of(
                "policyDigest", policyDigest,
                "logLevel", Map.of("values", List.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE")),
                "wireGraph", wireGraph
        );

        return new ContractDocument(root, "jmx-wire-contract", "unused", "unused", "SHA-256", 1);
    }
}
