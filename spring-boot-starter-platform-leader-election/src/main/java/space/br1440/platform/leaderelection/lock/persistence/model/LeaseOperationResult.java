package space.br1440.platform.leaderelection.lock.persistence.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.lang.Nullable;

import java.time.Instant;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LeaseOperationResult.Success.class, name = "SUCCESS"),
        @JsonSubTypes.Type(value = LeaseOperationResult.Conflict.class, name = "CONFLICT"),
        @JsonSubTypes.Type(value = LeaseOperationResult.NotFound.class, name = "NOT_FOUND"),
        @JsonSubTypes.Type(value = LeaseOperationResult.Failure.class, name = "FAILURE")
})
public sealed interface LeaseOperationResult permits LeaseOperationResult.Success, LeaseOperationResult.Conflict, LeaseOperationResult.NotFound, LeaseOperationResult.Failure {

    default boolean isSuccess() {
        return this instanceof Success;
    }

    record Success(String operationType,
                   Instant timestamp,
                   String leaseKey,
                   LeaseData data) implements LeaseOperationResult {
        public record LeaseData(@Nullable String holder,
                                @Nullable Long version,
                                @Nullable Integer leaseDuration,
                                @Nullable Instant acquireTime,
                                @Nullable Instant renewTime,
                                @Nullable Integer transitions,
                                @Nullable Integer ttlRemaining) {
        }
    }

    record Conflict(String operationType,
                    Instant timestamp,
                    String leaseKey,
                    ConflictDetails details) implements LeaseOperationResult {
        public record ConflictDetails(@Nullable String currentHolder,
                                      @Nullable Long currentVersion,
                                      @Nullable Integer ttlRemaining,
                                      @Nullable Long expectedVersion) {
        }
    }

    record NotFound(String operationType,
                    Instant timestamp,
                    String leaseKey) implements LeaseOperationResult {
    }

    record Failure(String operationType,
                   Instant timestamp,
                   String leaseKey,
                   FailureReason reason,
                   String message,
                   @Nullable String errorCode) implements LeaseOperationResult {
        public enum FailureReason {
            REDIS_DB_ERROR,
            PARSE_ERROR,
            VALIDATION_ERROR,
            SCRIPT_ERROR,
            UNKNOWN
        }
    }

}
