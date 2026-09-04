package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.Objects;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;

public final class ApplySnapshotRequestParameters {

    private final ExpectedSettingsRevision expectedBase;
    private final String attemptId;
    private final String sourceAuthority;
    private final String applyTrigger;
    private final String sourceRevision;
    private final CompleteTracingSettings policy;

    private ApplySnapshotRequestParameters(Builder builder) {
        this.expectedBase = Objects.requireNonNull(builder.expectedBase, "expectedBase");
        this.policy = Objects.requireNonNull(builder.policy, "policy");
        this.attemptId = requireNonBlank(builder.attemptId, "attemptId");
        this.sourceAuthority = requireNonBlank(builder.sourceAuthority, "sourceAuthority");
        this.applyTrigger = requireNonBlank(builder.applyTrigger, "applyTrigger");
        this.sourceRevision = Objects.requireNonNull(builder.sourceRevision, "sourceRevision");
    }

    public ExpectedSettingsRevision expectedBase() {
        return expectedBase;
    }

    public String attemptId() {
        return attemptId;
    }

    public String sourceAuthority() {
        return sourceAuthority;
    }

    public String applyTrigger() {
        return applyTrigger;
    }

    public String sourceRevision() {
        return sourceRevision;
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

        private ExpectedSettingsRevision expectedBase;
        private String attemptId;
        private String sourceAuthority;
        private String applyTrigger;
        private String sourceRevision = "";
        private CompleteTracingSettings policy;

        private Builder() {
        }

        public Builder expectedBase(ExpectedSettingsRevision expectedBase) {
            this.expectedBase = expectedBase;
            return this;
        }

        public Builder attemptId(String attemptId) {
            this.attemptId = attemptId;
            return this;
        }

        public Builder sourceAuthority(String sourceAuthority) {
            this.sourceAuthority = sourceAuthority;
            return this;
        }

        public Builder applyTrigger(String applyTrigger) {
            this.applyTrigger = applyTrigger;
            return this;
        }

        public Builder sourceRevision(String sourceRevision) {
            this.sourceRevision = sourceRevision;
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
