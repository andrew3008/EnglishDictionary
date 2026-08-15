package space.br1440.platform.tracing.autoconfigure.control.settings;

import space.br1440.platform.common.util.StringUtils;

public record ExpectedSettingsRevision(String runtimeInstanceId, long acceptedSequence) {

    public ExpectedSettingsRevision {
        if (StringUtils.isNullOrBlank(runtimeInstanceId)) {
            throw new IllegalArgumentException("runtimeInstanceId must not be blank");
        }

        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must be >= 0, got " + acceptedSequence);
        }
    }
}
