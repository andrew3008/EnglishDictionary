package space.br1440.platform.tracing.otel.javaagent.readiness;

import lombok.experimental.UtilityClass;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
public final class ReadinessSnapshotAssertions {

    public static ReadinessFailure failureOf(PlatformExtensionReadinessSnapshot snapshot) {
        assertThat(snapshot).isInstanceOf(PlatformExtensionReadinessSnapshot.Failed.class);
        return ((PlatformExtensionReadinessSnapshot.Failed) snapshot).failure();
    }

    public static PlatformExtensionReadinessSnapshot.Initializing initializingOf(PlatformExtensionReadinessSnapshot snapshot) {
        assertThat(snapshot).isInstanceOf(PlatformExtensionReadinessSnapshot.Initializing.class);
        return (PlatformExtensionReadinessSnapshot.Initializing) snapshot;
    }
}
