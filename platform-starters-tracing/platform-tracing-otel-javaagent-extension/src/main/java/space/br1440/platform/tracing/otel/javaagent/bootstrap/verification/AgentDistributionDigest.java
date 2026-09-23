package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

record AgentDistributionDigest(String hex) {

    private static final String ALGORITHM = "SHA-256";
    private static final Pattern CANONICAL_HEX = Pattern.compile("[0-9a-f]{64}");

    AgentDistributionDigest {
        Objects.requireNonNull(hex, "hex");
        if (!CANONICAL_HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("SHA-256 digest must be 64 lowercase hex characters");
        }
    }

    static Optional<AgentDistributionDigest> parse(String candidate) {
        if (candidate == null) {
            return Optional.empty();
        }

        String normalized = candidate.toLowerCase(Locale.ROOT);
        return CANONICAL_HEX.matcher(normalized).matches()
                ? Optional.of(new AgentDistributionDigest(normalized))
                : Optional.empty();
    }

    static AgentDistributionDigest of(Path file) throws IOException {
        Objects.requireNonNull(file, "file");

        MessageDigest sha256 = newSha256();
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), sha256)) {
            input.transferTo(OutputStream.nullOutputStream());
        }

        String hexCode = HexFormat.of().formatHex(sha256.digest());
        return new AgentDistributionDigest(hexCode);
    }

    @Override
    public String toString() {
        return hex;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ALGORITHM + " is not available in this JVM", exception);
        }
    }
}
