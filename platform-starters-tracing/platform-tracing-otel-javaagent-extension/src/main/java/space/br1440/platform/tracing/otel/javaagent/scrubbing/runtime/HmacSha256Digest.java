package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class HmacSha256Digest {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;
    private final ThreadLocal<Mac> threadLocalMac;

    private HmacSha256Digest(SecretKeySpec key) {
        this.key = key;
        this.threadLocalMac = ThreadLocal.withInitial(this::newMac);
    }

    static HmacSha256Digest from(byte[] ownedKey) {
        Objects.requireNonNull(ownedKey, "ownedKey");

        try {
            SecretKeySpec key = new SecretKeySpec(ownedKey, HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return new HmacSha256Digest(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize HMAC-SHA256", e);
        } finally {
            Arrays.fill(ownedKey, (byte) 0);
        }
    }

    String hashHex(String value) {
        Objects.requireNonNull(value, "value");

        byte[] macBytes = threadLocalMac.get().doFinal(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(macBytes);
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize HMAC-SHA256", e);
        }
    }
}
