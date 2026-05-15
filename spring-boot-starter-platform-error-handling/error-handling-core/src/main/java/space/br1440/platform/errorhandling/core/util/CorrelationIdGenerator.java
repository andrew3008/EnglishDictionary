package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Генератор UUID v4 (RFC 4122) для correlation-id, общий для MVC и WebFlux стратегий.
 *
 * <h2>Источник энтропии</h2>
 * <p>{@link SecureRandom#getInstanceStrong()} — strong-алгоритм, заявленный в
 * {@code java.security} (см. {@code securerandom.strongAlgorithms}). Соответствует
 * требованиям FIPS 140-3 и FAPI 2.0 для генерации идентификаторов запросов в
 * compliance-критичных средах (банки, госы, финтех).
 *
 * <p>Если strong-алгоритм недоступен (минимальная JDK без {@code NativePRNGNonBlocking},
 * нестандартный JCE-провайдер) — fallback на {@code new SecureRandom()}, с WARN в лог
 * для прод-наблюдаемости. {@link UUID#randomUUID()}, использующий внутренний
 * {@code java.util.Random}-источник, намеренно НЕ применяется: его энтропия не
 * подходит для compliance-критичных сценариев.
 *
 * <h2>Стартовая стоимость</h2>
 * <p>Инициализация выполняется ОДИН РАЗ в {@code static {}} блоке при загрузке класса.
 * На low-entropy окружениях (свежий Kubernetes pod на VM без RDRAND) это может занять
 * 1–3 секунды (блокировка на {@code /dev/random}). Альтернатива — блокировка ПЕРВОГО
 * error-response при первом вызове {@code getInstanceStrong()} — хуже для SLO.
 *
 * <h2>Thread safety</h2>
 * <p>{@link SecureRandom} thread-safe начиная с Java 8 (внутренние synchronized).
 * Один статический экземпляр обслуживает всё приложение, без contention на hot-path:
 * {@code nextBytes(byte[16])} — это микросекундная операция.
 */
@UtilityClass
public final class CorrelationIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGenerator.class);

    /**
     * Strong SecureRandom, инициализируемый один раз при загрузке класса.
     * См. {@link SecureRandom#getInstanceStrong()} и {@code securerandom.strongAlgorithms}
     * в {@code java.security}.
     */
    private static final SecureRandom SECURE_RANDOM;

    static {
        SecureRandom strong;
        try {
            strong = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            // Strong-алгоритм отсутствует в текущей JDK/JCE. Это редкая ситуация
            // (минимальные кастомные JRE), но fallback нужен — иначе стартер
            // не загрузится. В лог пишем WARN с подсказкой, что проверять.
            log.warn("SecureRandom.getInstanceStrong() unavailable, falling back to default SecureRandom. " +
                    "For compliance-critical environments verify 'securerandom.strongAlgorithms' " +
                    "in java.security and the presence of a validated JCE provider.");
            strong = new SecureRandom();
        }
        SECURE_RANDOM = strong;
    }

    /**
     * Генерирует UUID v4 (RFC 4122 §4.4) с случайными байтами из {@link #SECURE_RANDOM}.
     *
     * <p>Layout биты приводим к каноническому v4 ({@code msb[12..15]=0100},
     * {@code lsb[6..7]=10}) — иначе UUID не пройдёт валидацию по {@link UUID#version()}.
     */
    @NonNull
    public static String generate() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);

        // RFC 4122 §4.4: версия = 4 (старшие 4 бита 7-го байта = 0100)
        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x40);
        // RFC 4122 §4.4: variant = 10 (старшие 2 бита 9-го байта)
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

        long msb = bytesToLong(bytes, 0);
        long lsb = bytesToLong(bytes, 8);
        return new UUID(msb, lsb).toString();
    }

    private static long bytesToLong(@NonNull byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }
}
