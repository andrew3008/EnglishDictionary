package space.br1440.platform.tracing.autoconfigure.control.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class CompleteTracingSettingsTest {

    private static CompleteTracingSettings.Builder fullBuilder() {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO");
    }

    @Test
    void builderProducesAllSevenLeaves() {
        CompleteTracingSettings s = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5)
                .samplingRouteRatios(Map.of("/api", 0.1))
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("DEBUG")
                .build();

        assertThat(s.samplingEnabled()).isTrue();
        assertThat(s.samplingRatio()).isEqualTo(0.5);
        assertThat(s.samplingRouteRatios()).containsEntry("/api", 0.1);
        assertThat(s.validationLevel()).isEqualTo("WARNING");
        assertThat(s.exporterEnabled()).isTrue();
        assertThat(s.traceControlHeadersEnabled()).isTrue();
        assertThat(s.diagnosticsLogLevel()).isEqualTo("DEBUG");
    }

    @Test
    void withMethodsProduceCopyWithOneDiff() {
        CompleteTracingSettings original = fullBuilder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .exporterEnabled(true)
                .build();

        CompleteTracingSettings modified = original.withSamplingEnabled(false);
        assertThat(modified.samplingEnabled()).isFalse();
        assertThat(modified.samplingRatio()).isEqualTo(original.samplingRatio());
        assertThat(modified.exporterEnabled()).isEqualTo(original.exporterEnabled());
    }

    @Test
    void snapshotIsImmutable() {
        Map<String, Double> mutable = new HashMap<>();
        mutable.put("/api", 0.5);
        CompleteTracingSettings s = fullBuilder()
                .samplingRouteRatios(mutable)
                .build();
        mutable.put("/other", 0.9);
        assertThat(s.samplingRouteRatios()).doesNotContainKey("/other");
    }

    @Test
    void equalSnapshotsAreEqual() {
        CompleteTracingSettings a = fullBuilder().build();
        CompleteTracingSettings b = fullBuilder().build();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeSamplingRatio() {
        assertThatThrownBy(() -> fullBuilder().samplingRatio(Double.NaN).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fullBuilder().samplingRatio(Double.POSITIVE_INFINITY).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fullBuilder().samplingRatio(-0.1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fullBuilder().samplingRatio(1.1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalizesNegativeZeroBeforePublication() {
        CompleteTracingSettings s = fullBuilder()
                .samplingRatio(-0.0d)
                .samplingRouteRatios(Map.of("/z", -0.0d))
                .build();

        assertThat(Double.doubleToRawLongBits(s.samplingRatio()))
                .isEqualTo(Double.doubleToRawLongBits(0.0d));
        assertThat(Double.doubleToRawLongBits(s.samplingRouteRatios().get("/z")))
                .isEqualTo(Double.doubleToRawLongBits(0.0d));
    }

    @Test
    void rejectsNullBlankControlAndOversizedRoutePrefixesWithoutEcho() {
        // null в builder — completeness (лист не задан), не value-invariant.
        assertThatThrownBy(() -> fullBuilder().samplingRouteRatios(null).build())
                .isInstanceOf(TracingSettingsManifestException.class);

        // null через with* — value-invariant на уже полном snapshot.
        assertThatThrownBy(() -> fullBuilder().build().withSamplingRouteRatios(null))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Double> withNullKey = new HashMap<>();
        withNullKey.put(null, 0.5);
        assertThatThrownBy(() -> fullBuilder().samplingRouteRatios(withNullKey).build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> fullBuilder().samplingRouteRatios(Map.of("   ", 0.5)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("   ");

        String canary = "/secret-route-canary";
        assertThatThrownBy(() -> fullBuilder()
                .samplingRouteRatios(Map.of(canary + "\u0001", 0.5))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(canary);

        String tooLong = "a".repeat(256);
        assertThatThrownBy(() -> fullBuilder().samplingRouteRatios(Map.of(tooLong, 0.5)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(tooLong);
    }

    @Test
    void rejectsOversizedRouteMapAndNullOrInvalidRouteRatio() {
        Map<String, Double> oversized = new HashMap<>();
        for (int i = 0; i < 101; i++) {
            oversized.put("/r" + i, 0.5);
        }
        assertThatThrownBy(() -> fullBuilder().samplingRouteRatios(oversized).build())
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Double> nullRatio = new HashMap<>();
        nullRatio.put("/api", null);
        assertThatThrownBy(() -> fullBuilder().samplingRouteRatios(nullRatio).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("/api");

        assertThatThrownBy(() -> fullBuilder().samplingRouteRatios(Map.of("/api", 1.5)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("/api");
    }

    @Test
    void acceptsExactMaximumRouteBounds() {
        Map<String, Double> maximum = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            maximum.put("/r" + i, 0.5);
        }
        assertThat(fullBuilder().samplingRouteRatios(maximum).build().samplingRouteRatios())
                .hasSize(100);

        String exact = "a".repeat(255);
        assertThat(fullBuilder().samplingRouteRatios(Map.of(exact, 0.5)).build()
                .samplingRouteRatios()).containsKey(exact);
    }

    @Test
    void rejectsRoutePrefixExceedingUtf8ByteLimit() {
        // given
        String routePrefix = "я".repeat(128);

        // when
        Throwable failure = catchThrowable(() -> fullBuilder()
                .samplingRouteRatios(Map.of(routePrefix, 0.5d))
                .build());

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 255 UTF-8 bytes")
                .hasMessageNotContaining(routePrefix);
    }

    @Test
    void canonicalizesLogLevelCaseInsensitivelyAndRejectsWhitespace() {
        assertThat(fullBuilder().diagnosticsLogLevel("info").build().diagnosticsLogLevel())
                .isEqualTo("INFO");
        assertThatThrownBy(() -> fullBuilder().diagnosticsLogLevel(" INFO ").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fullBuilder().diagnosticsLogLevel("VERBOSE").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("VERBOSE");
    }

    @Test
    void canonicalizesValidationLevelCaseInsensitively() {
        // given
        CompleteTracingSettings.Builder builder = fullBuilder().validationLevel("warning");

        // when
        CompleteTracingSettings settings = builder.build();

        // then
        assertThat(settings.validationLevel()).isEqualTo("WARNING");
    }

    @Test
    void rejectsUnknownValidationLevel() {
        // given
        CompleteTracingSettings.Builder builder = fullBuilder().validationLevel("STRICT");

        // when
        Throwable failure = catchThrowable(builder::build);

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("STRICT");
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void logLevelCanonicalizationUsesRootLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // В турецкой локали "i".toUpperCase() → "İ", что сломало бы allowlist без Locale.ROOT.
            assertThat(fullBuilder().diagnosticsLogLevel("info").build().diagnosticsLogLevel())
                    .isEqualTo("INFO");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void toStringReportsRouteCountWithoutRouteKeys() {
        String route = "/canary/secret/z9Qx7mK2vL4pR8nW";
        String text = fullBuilder()
                .samplingRouteRatios(Map.of(route, 0.25d))
                .build()
                .toString();

        assertThat(text)
                .contains("sampling.route-ratios.size=1")
                .doesNotContain(route);
    }

    @Test
    void incompleteBuilderStillThrowsManifestException() {
        assertThatThrownBy(() -> CompleteTracingSettings.builder().samplingEnabled(true).build())
                .isInstanceOf(TracingSettingsManifestException.class);
    }
}
