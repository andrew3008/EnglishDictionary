package space.br1440.platform.leaderelection.lock.persistence.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

@Getter
@ToString
public final class LeaseRecord implements Comparable<LeaseRecord> {

    private static final Comparator<LeaseRecord> COMPARATOR =
            Comparator.comparing(LeaseRecord::getHolderIdentity, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(LeaseRecord::getAcquireTime, Comparator.nullsFirst(Instant::compareTo))
                    .thenComparing(LeaseRecord::getRenewTime, Comparator.nullsFirst(Instant::compareTo))
                    .thenComparing(LeaseRecord::getLeaseDurationSeconds)
                    .thenComparing(LeaseRecord::getLeaderTransitions);

    private final String holderIdentity;
    private final Instant acquireTime;
    private final Instant renewTime;
    private final int leaseDurationSeconds;
    private final int leaderTransitions;
    private final int version;

    public LeaseRecord() {
        this.holderIdentity = "";
        this.acquireTime = Instant.MIN;
        this.renewTime = Instant.MIN;
        this.leaseDurationSeconds = 0;
        this.leaderTransitions = 0;
        this.version = 0;
    }

    @JsonCreator
    public LeaseRecord(@JsonProperty("holderIdentity") String holderIdentity,
                       @JsonProperty("acquireTime") Instant acquireTime,
                       @JsonProperty("renewTime") Instant renewTime,
                       @JsonProperty("leaseDurationSeconds") int leaseDurationSeconds,
                       @JsonProperty("leaderTransitions") int leaderTransitions,
                       @JsonProperty("version") int version) {
        this.holderIdentity = Objects.requireNonNull(holderIdentity, "holderIdentity cannot be null");
        this.acquireTime = Objects.requireNonNull(acquireTime, "acquireTime cannot be null");
        this.renewTime = Objects.requireNonNull(renewTime, "renewTime cannot be null");
        this.leaseDurationSeconds = leaseDurationSeconds;
        this.leaderTransitions = leaderTransitions;
        this.version = version;
    }

    public LeaseRecord withRenewTime(Instant newRenewTime) {
        return new LeaseRecord(
                holderIdentity,
                acquireTime,
                newRenewTime,
                leaseDurationSeconds,
                leaderTransitions,
                version
        );
    }

    @Override
    public int compareTo(@NotNull LeaseRecord other) {
        return COMPARATOR.compare(this, other);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof LeaseRecord other)) {
            return false;
        }

        return new EqualsBuilder()
                .append(holderIdentity, other.holderIdentity)
                .append(acquireTime, other.acquireTime)
                .append(renewTime, other.renewTime)
                .append(leaseDurationSeconds, other.leaseDurationSeconds)
                .append(leaderTransitions, other.leaderTransitions)
                .append(version, other.version)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37)
                .append(holderIdentity)
                .append(acquireTime)
                .append(renewTime)
                .append(leaseDurationSeconds)
                .append(leaderTransitions)
                .append(version)
                .toHashCode();
    }

}
