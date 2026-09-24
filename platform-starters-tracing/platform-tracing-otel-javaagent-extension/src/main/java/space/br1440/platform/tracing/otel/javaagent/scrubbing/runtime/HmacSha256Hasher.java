package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 с одним {@code SecretKeySpec} на все потоки.
 * <p>
 * {@code from} забирает переданный массив во владение и обнуляет его в {@code finally}.
 * {@code SecretKeySpec} хранит собственную копию ключа, а каждый {@code Mac} в {@code ThreadLocal}
 * — производное состояние. Обнуление сокращает число копий в heap и не устраняет их.
 * Пустой ключ на этом пути не ожидается: {@code MaskedSecret.of} не создаёт present-секрет
 * из blank-строки, а {@code copyBytes} для present-секрета возвращает непустой массив.
 */
final class HmacSha256Hasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final SecretKeySpec key;
    private final ThreadLocal<Mac> threadLocalMac;

    private HmacSha256Hasher(SecretKeySpec key) {
        this.key = key;
        this.threadLocalMac = ThreadLocal.withInitial(this::newMac);
    }

    static HmacSha256Hasher from(byte[] ownedKey) {
        Objects.requireNonNull(ownedKey, "ownedKey");
        try {
            SecretKeySpec key = new SecretKeySpec(ownedKey, HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return new HmacSha256Hasher(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize HMAC-SHA256", e);
        } finally {
            Arrays.fill(ownedKey, (byte) 0);
        }
    }

    String hashHex(String value) {
        Mac mac = threadLocalMac.get();
        byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xFF;
            hex[i * 2] = HEX[b >>> 4];
            hex[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(hex);
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
