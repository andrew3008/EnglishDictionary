package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.CollectionUtils;

import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ConfigServerTracingSettingsAdapter {

    private static final String POLICY_KEY_PREFIX = "platform.tracing.policy.";

    private final ConfigServerTracingSettingsSynchronizer synchronizer;

    public ConfigServerTracingSettingsAdapter(ConfigServerTracingSettingsSynchronizer synchronizer) {
        this.synchronizer = Objects.requireNonNull(synchronizer, "synchronizer");
    }

    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        Objects.requireNonNull(event, "event");

        List<String> relevantKeys = relevantPolicyKeys(event.getKeys());
        if (relevantKeys.isEmpty()) {
            return;
        }

        log.debug(
                "EnvironmentChangeEvent changed {} platform.tracing.policy.* key(s) {}; triggering synchronous refresh",
                relevantKeys.size(),
                relevantKeys
        );

        try {
            synchronizer.refresh();
        } catch (TracingSettingsManifestException | IllegalArgumentException invalidManifest) {
            log.error("Config Server manifest rejected locally; LKG retained: {}", invalidManifest.getMessage());
        } catch (RuntimeException failure) {
            log.error("Config Server settings refresh failed; LKG retained. errorType={}",
                    failure.getClass().getName(), failure);
        }
    }

    private static List<String> relevantPolicyKeys(Set<String> changedKeys) {
        if (CollectionUtils.isEmpty(changedKeys)) {
            return List.of();
        }

        return changedKeys.stream()
                .filter(Objects::nonNull)
                .filter(key -> key.startsWith(POLICY_KEY_PREFIX))
                .sorted()
                .toList();
    }
}
