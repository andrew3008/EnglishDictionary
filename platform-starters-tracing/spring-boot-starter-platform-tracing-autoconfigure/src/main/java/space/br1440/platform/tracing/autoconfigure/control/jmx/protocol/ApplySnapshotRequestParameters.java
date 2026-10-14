package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.Objects;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;

public final class ApplySnapshotRequestParameters {

    private final SettingsRevision expectedBase;
    private final String attemptId;
    private final String writer;
    private final CompleteTracingSettings policy;

    private ApplySnapshotRequestParameters(Builder builder) {
        this.expectedBase = Objects.requireNonNull(builder.expectedBase, "expectedBase");
        this.policy = Objects.requireNonNull(builder.policy, "policy");
        this.attemptId = requireNonBlank(builder.attemptId, "attemptId");
        this.writer = requireNonBlank(builder.writer, "writer");
    }

    public SettingsRevision expectedBase() {
        return expectedBase;
    }

    public String attemptId() {
        return attemptId;
    }

    public String writer() {
        return writer;
    }

    public CompleteTracingSettings policy() {
        return policy;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (StringUtils.isNullOrBlank(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value;
    }

    public static final class Builder {

        private SettingsRevision expectedBase;
        private String attemptId;
        private String writer;
        private CompleteTracingSettings policy;

        private Builder() {
        }

        public Builder expectedBase(SettingsRevision expectedBase) {
            this.expectedBase = expectedBase;
            return this;
        }

        public Builder attemptId(String attemptId) {
            this.attemptId = attemptId;
            return this;
        }

        public Builder writer(String writer) {
            this.writer = writer;
            return this;
        }

        public Builder policy(CompleteTracingSettings policy) {
            this.policy = policy;
            return this;
        }

        public ApplySnapshotRequestParameters build() {
            return new ApplySnapshotRequestParameters(this);
        }
    }
}
