package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import lombok.experimental.UtilityClass;

@UtilityClass
final class PlatformOtelAcpPinHashes {

    static final int FORMAT_VERSION = 1;
    static final Comparator<String> NAME_ORDER = Comparator.naturalOrder();

    static String sha256Hex(Collection<String> names) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(NAME_ORDER);

        StringBuilder preimage = new StringBuilder();
        for (String name : sorted) {
            preimage.append(name);
            preimage.append('\n');
        }
        return sha256Hex(preimage.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }

        byte[] hashed = digest.digest(bytes);

        StringBuilder hex = new StringBuilder(hashed.length * 2);
        for (byte value : hashed) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return hex.toString();
    }
}
