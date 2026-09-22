package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import lombok.experimental.UtilityClass;

@UtilityClass
final class Sha256Digest {

    private static final int BUFFER_SIZE = 64 * 1024;

    static String hexOf(Path file) throws IOException {
        MessageDigest digest = newDigest();

        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream stream = Files.newInputStream(file)) {
            int read = stream.read(buffer);
            while (read != -1) {
                digest.update(buffer, 0, read);
                read = stream.read(buffer);
            }
        }

        return toHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", failure);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }

        return result.toString();
    }
}
