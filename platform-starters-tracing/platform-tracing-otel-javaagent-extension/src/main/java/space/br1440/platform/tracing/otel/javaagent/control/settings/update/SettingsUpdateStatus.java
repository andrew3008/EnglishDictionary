package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

public enum SettingsUpdateStatus {
    APPLIED,
    UNCHANGED,
    REJECTED,
    STALE,
    FAILED;

    public boolean isSuccessful() {
        return (this == APPLIED) || (this == UNCHANGED);
    }
}
