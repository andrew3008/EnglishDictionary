package space.br1440.platform.tracing.e2e;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettingsAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;

final class PackagedConfigServerScenario {

    static final String INITIAL_APPLIED_TOKEN = "PACKAGED_CONFIG_SERVER_INITIAL_APPLIED";
    static final String REFRESH_APPLIED_TOKEN = "PACKAGED_CONFIG_SERVER_REFRESH_APPLIED";
    static final String REPEAT_UNCHANGED_TOKEN = "PACKAGED_CONFIG_SERVER_REPEAT_UNCHANGED";
    static final String INVALID_REJECTED_TOKEN = "PACKAGED_CONFIG_SERVER_INVALID_REJECTED_LKG";

    private static final String SCENARIO_PROPERTY = "platformTracing.packagedScenario";
    private static final String CONFIG_SERVER_SCENARIO = "CONFIG_SERVER";
    private static final String PROPERTY_SOURCE_NAME = "configserver:packaged-certification";
    private static final String SAMPLING_ENABLED = "platform.tracing.policy.sampling.enabled";
    private static final String SAMPLING_RATIO = "platform.tracing.policy.sampling.ratio";
    private static final String VALIDATION_LEVEL = "platform.tracing.policy.validation.level";
    private static final String EXPORTER_ENABLED = "platform.tracing.policy.exporter.enabled";
    private static final String TRACE_CONTROL_HEADERS_ENABLED = "platform.tracing.policy.propagation.trace-control-headers.enabled";
    private static final String DIAGNOSTICS_LOG_LEVEL = "platform.tracing.policy.diagnostics.log-level";

    private final boolean enabled;
    private final Map<String, Object> policyValues;

    private PackagedConfigServerScenario(boolean enabled) {
        this.enabled = enabled;
        this.policyValues = initialPolicy();
    }

    static PackagedConfigServerScenario fromSystemProperties() {
        return new PackagedConfigServerScenario(
                CONFIG_SERVER_SCENARIO.equals(System.getProperty(SCENARIO_PROPERTY))
        );
    }

    void initialize(ConfigurableApplicationContext context) {
        if (!enabled) {
            return;
        }

        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, policyValues));
    }

    void verify(ConfigurableApplicationContext context) {
        if (!enabled) {
            return;
        }

        TracingSettingsWriter settingsWriter = context.getBean(TracingSettingsWriter.class);
        AcceptedSettings initial = current(settingsWriter, "Initial Config Server delivery");
        require(initial.authority() == AcceptedSettingsAuthority.CONFIG_SERVER,
                "Initial Config Server delivery did not acquire CONFIG_SERVER authority");
        require(initial.revision().acceptedSequence() > 0L,
                "Initial Config Server delivery did not advance accepted sequence");
        require(Double.compare(initial.policy().samplingRatio(), 1.0d) == 0,
                "Initial Config Server delivery did not apply the configured sampling ratio");
        System.out.println(INITIAL_APPLIED_TOKEN);

        policyValues.put(SAMPLING_RATIO, "0.25");
        publishPolicyChange(context);

        AcceptedSettings refreshed = current(settingsWriter, "Config Server refresh");
        require(refreshed.authority() == AcceptedSettingsAuthority.CONFIG_SERVER,
                "Config Server refresh lost CONFIG_SERVER authority");
        require(refreshed.revision().acceptedSequence() == initial.revision().acceptedSequence() + 1L,
                "Config Server refresh did not advance accepted sequence exactly once");
        require(Double.compare(refreshed.policy().samplingRatio(), 0.25d) == 0,
                "Config Server refresh did not apply the updated sampling ratio");
        System.out.println(REFRESH_APPLIED_TOKEN);

        publishPolicyChange(context);

        AcceptedSettings unchanged = current(settingsWriter, "Repeated Config Server refresh");
        require(unchanged.revision().equals(refreshed.revision()),
                "Repeated Config Server refresh changed accepted revision");
        require(unchanged.policy().equals(refreshed.policy()),
                "Repeated Config Server refresh changed accepted policy");
        System.out.println(REPEAT_UNCHANGED_TOKEN);

        policyValues.put(SAMPLING_RATIO, "1.5");
        publishPolicyChange(context);

        AcceptedSettings rejected = current(settingsWriter, "Invalid Config Server refresh");
        require(rejected.revision().equals(refreshed.revision()),
                "Invalid Config Server refresh changed accepted revision");
        require(rejected.policy().equals(refreshed.policy()),
                "Invalid Config Server refresh replaced the last-known-good policy");
        System.out.println(INVALID_REJECTED_TOKEN);
    }

    private static Map<String, Object> initialPolicy() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SAMPLING_ENABLED, "true");
        values.put(SAMPLING_RATIO, "1.0");
        values.put(VALIDATION_LEVEL, "WARNING");
        values.put(EXPORTER_ENABLED, "true");
        values.put(TRACE_CONTROL_HEADERS_ENABLED, "true");
        values.put(DIAGNOSTICS_LOG_LEVEL, "INFO");
        return values;
    }

    private static AcceptedSettings current(TracingSettingsWriter settingsWriter, String operation) {
        return settingsWriter.current()
                .orElseThrow(() -> new IllegalStateException(operation + " left accepted settings unreadable"));
    }

    private static void publishPolicyChange(ConfigurableApplicationContext context) {
        context.publishEvent(new EnvironmentChangeEvent(context, Set.of(SAMPLING_RATIO)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
