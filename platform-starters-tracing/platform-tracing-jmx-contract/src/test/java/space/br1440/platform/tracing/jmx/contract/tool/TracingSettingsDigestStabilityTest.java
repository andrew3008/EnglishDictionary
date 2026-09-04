package space.br1440.platform.tracing.jmx.contract.tool;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        result.put("all-false-boundary-zero", "410e3cb303a09ba87833cdde5f3749a50c79fc82812c32dcf80cf1e1d9c9ceaf");
        result.put("all-true-boundary-one-with-routes", "205b1f4b37ae0793ee42d7a90e8e116d08d61372ff8b1a9c9a404d5dd82a39b9");
        result.put("mixed-negative-zero-route", "2fa206ae150e21e8c5e27a648f4f6e85d72ec8e9f9d6addfdb5852673df6c75a");
        return result;
    }

    private static Map<String, DigestCase> cases() {
        Map<String, DigestCase> result = new LinkedHashMap<>();
        result.put("all-false-boundary-zero", new DigestCase(
                false, 0.0, Map.of(), false, false, false, false, "OFF"
        ));

        result.put("all-true-boundary-one-with-routes", new DigestCase(
                true, 1.0, Map.of("/b", 0.5, "/a", 0.25), true, true, true, true, "TRACE"
        ));

        result.put("mixed-negative-zero-route", new DigestCase(
                true, 0.5, Map.of("/route", -0.0), false, true, false, true, "DEBUG"
        ));

        return result;
    }

    private record DigestCase(boolean samplingEnabled,
                              double samplingRatio,
                              Map<String, Double> samplingRouteRatios,
                              boolean validationEnabled,
                              boolean validationStrict,
                              boolean exporterEnabled,
                              boolean traceControlHeadersEnabled,
                              String diagnosticsLogLevel) {
    }

    private static String invokeSha256(Class<?> generated, DigestCase digestCase) throws Exception {
        Method method = generated.getMethod(
                "sha256", boolean.class, double.class, Map.class,
                boolean.class, boolean.class, boolean.class, boolean.class, String.class
        );

        try {
            return (String) method.invoke(
                    null,
                    digestCase.samplingEnabled(),
                    digestCase.samplingRatio(),
                    digestCase.samplingRouteRatios(),
                    digestCase.validationEnabled(),
                    digestCase.validationStrict(),
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
                "encodingVersion", 0,
                "validLogLevels", List.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE")
        );

        Map<String, Object> wireGraph = Map.of(
                "maxRouteEntries", 64,
                "maxRoutePrefixUtf8Bytes", 256
        );

        Map<String, Object> root = Map.of(
                "policyDigest", policyDigest,
                "wireGraph", wireGraph
        );

        return new ContractDocument(root, "jmx-wire-contract", "unused", "unused", "SHA-256", 1);
    }
}
