package space.br1440.platform.errorhandling.core.util.http;

import org.springframework.lang.NonNull;

import java.util.Objects;
import java.util.Optional;

public record UpstreamOutcome(@NonNull Optional<Integer> httpStatus, @NonNull Optional<UpstreamIdentity> identity) {

    private static final UpstreamOutcome NO_RESPONSE = new UpstreamOutcome(Optional.empty(), Optional.empty());

    public UpstreamOutcome {
        Objects.requireNonNull(httpStatus, "httpStatus must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
    }

    @NonNull
    public static UpstreamOutcome noResponse() {
        return NO_RESPONSE;
    }

    @NonNull
    public static UpstreamOutcome ofStatus(int httpStatus) {
        return new UpstreamOutcome(Optional.of(httpStatus), Optional.empty());
    }

    @NonNull
    public static UpstreamOutcome ofRecognized(int httpStatus, @NonNull UpstreamIdentity identity) {
        return new UpstreamOutcome(Optional.of(httpStatus), Optional.of(identity));
    }
}
