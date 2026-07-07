package space.br1440.platform.tracing.autoconfigure.jmx;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingObjectNames;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link PlatformTracingJmxObjectNames} (autoconfigure / App CL side) and
 * {@link PlatformTracingObjectNames} (otel-extension / Agent CL side) declare identical
 * ObjectName strings for all six domains.
 * <p>
 * This test catches drift between the two constant sets that would break JMX routing
 * at runtime (they cannot share a class due to classloader isolation but must be identical).
 */
class ObjectNameConstantsConsistencyTest {

    @Test
    void sampling_objectName_strings_are_equal() {
        assertThat(PlatformTracingJmxObjectNames.SAMPLING_OBJECT_NAME_STR)
                .isEqualTo(PlatformTracingObjectNames.SAMPLING_OBJECT_NAME_STR);
    }

    @Test
    void scrubbing_objectName_strings_are_equal() {
        assertThat(PlatformTracingJmxObjectNames.SCRUBBING_OBJECT_NAME_STR)
                .isEqualTo(PlatformTracingObjectNames.SCRUBBING_OBJECT_NAME_STR);
    }

    @Test
    void validation_objectName_strings_are_equal() {
        assertThat(PlatformTracingJmxObjectNames.VALIDATION_OBJECT_NAME_STR)
                .isEqualTo(PlatformTracingObjectNames.VALIDATION_OBJECT_NAME_STR);
    }

    @Test
    void export_objectName_strings_are_equal() {
        assertThat(PlatformTracingJmxObjectNames.EXPORT_OBJECT_NAME_STR)
                .isEqualTo(PlatformTracingObjectNames.EXPORT_OBJECT_NAME_STR);
    }

    @Test
    void processorMetrics_objectName_strings_are_equal() {
        assertThat(PlatformTracingJmxObjectNames.PROCESSOR_METRICS_OBJECT_NAME_STR)
                .isEqualTo(PlatformTracingObjectNames.PROCESSOR_METRICS_OBJECT_NAME_STR);
    }

    @Test
    void diagnostics_objectName_strings_are_equal() {
        assertThat(PlatformTracingJmxObjectNames.DIAGNOSTICS_OBJECT_NAME_STR)
                .isEqualTo(PlatformTracingObjectNames.DIAGNOSTICS_OBJECT_NAME_STR);
    }

    @Test
    void all_six_objectName_instances_match_string_constants() {
        assertThat(PlatformTracingJmxObjectNames.SAMPLING.toString())
                .isEqualTo(PlatformTracingJmxObjectNames.SAMPLING_OBJECT_NAME_STR);
        assertThat(PlatformTracingJmxObjectNames.SCRUBBING.toString())
                .isEqualTo(PlatformTracingJmxObjectNames.SCRUBBING_OBJECT_NAME_STR);
        assertThat(PlatformTracingJmxObjectNames.VALIDATION.toString())
                .isEqualTo(PlatformTracingJmxObjectNames.VALIDATION_OBJECT_NAME_STR);
        assertThat(PlatformTracingJmxObjectNames.EXPORT.toString())
                .isEqualTo(PlatformTracingJmxObjectNames.EXPORT_OBJECT_NAME_STR);
        assertThat(PlatformTracingJmxObjectNames.PROCESSOR_METRICS.toString())
                .isEqualTo(PlatformTracingJmxObjectNames.PROCESSOR_METRICS_OBJECT_NAME_STR);
        assertThat(PlatformTracingJmxObjectNames.DIAGNOSTICS.toString())
                .isEqualTo(PlatformTracingJmxObjectNames.DIAGNOSTICS_OBJECT_NAME_STR);
    }
}
