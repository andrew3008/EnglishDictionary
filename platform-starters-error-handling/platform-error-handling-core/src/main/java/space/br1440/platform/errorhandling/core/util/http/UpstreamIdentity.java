package space.br1440.platform.errorhandling.core.util.http;

import org.springframework.lang.NonNull;

import java.util.Objects;
import java.util.Optional;

public record UpstreamIdentity(@NonNull String errorCode, @NonNull Optional<String> domain) {

    public UpstreamIdentity {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
    }

    @NonNull
    public static UpstreamIdentity of(@NonNull String errorCode) {
        return new UpstreamIdentity(errorCode, Optional.empty());
    }

    @NonNull
    public static UpstreamIdentity of(@NonNull String errorCode, @NonNull String domain) {
        return new UpstreamIdentity(errorCode, Optional.of(domain));
    }
}
