package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import lombok.experimental.UtilityClass;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.AttestationFailures.failure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

@UtilityClass
final class AgentDistributionHasher {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final int BUFFER_BYTES = 16 * 1024;

    static boolean isSha256Digest(String candidate) {
        return SHA256.matcher(candidate).matches();
    }

    static String sha256(Path path, AttestationFailureCode failureCode, String failureMessage) {
        try (InputStream input = Files.newInputStream(path)) {
            return sha256(input);
        } catch (IOException exception) {
            throw failure(failureCode, failureMessage, exception);
        }
    }

    private static String sha256(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }

        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }
}
