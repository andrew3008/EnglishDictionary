package space.br1440.platform.errorhandling.blockhound;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * Кастомный BlockHound-интегратор для error-handling-webflux.
 *
 * <h2>Назначение</h2>
 * <p>BlockHound при старте инструментирует все методы JDK, которые могут
 * блокировать поток ({@code Thread.sleep}, {@code Object.wait}, IO),
 * и падает с {@link reactor.blockhound.BlockingOperationError}, если они
 * вызваны на потоках Reactor non-blocking scheduler'ов
 * ({@code parallel}, {@code single}).
 *
 * <h2>Allowlist</h2>
 * <p>Здесь разрешаются только те блокирующие операции, которые:
 * <ul>
 *   <li>исполняются один раз на старте JVM (статическая инициализация SLF4J,
 *       прогрев Jackson reflection cache);</li>
 *   <li>находятся в стороннем коде, который мы не можем переписать;</li>
 *   <li>гарантированно не блокируют под нагрузкой (только cold-path).</li>
 * </ul>
 * <p>Любая блокировка в нашем коде ({@code space.br1440.*}) <b>никогда</b>
 * не попадает в allowlist — она должна быть устранена в исходниках.
 *
 * <h2>Регистрация</h2>
 * <p>SPI-механизм: класс прописан в
 * {@code META-INF/services/reactor.blockhound.integration.BlockHoundIntegration}
 * и подхватывается автоматически при {@code BlockHound.install()}.
 */
public final class ErrorHandlingBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        // 1. Logback/SLF4J: при первом логировании в новом потоке инициализируется
        //    AppenderBase (synchronized) — это разовая операция cold-path.
        builder.allowBlockingCallsInside("ch.qos.logback.classic.Logger", "buildLoggingEventAndAppend");
        builder.allowBlockingCallsInside("ch.qos.logback.core.AppenderBase", "doAppend");
        builder.allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "subAppend");
        builder.allowBlockingCallsInside("ch.qos.logback.core.encoder.LayoutWrappingEncoder", "encode");

        // 2. Jackson: reflection-cache (POJOPropertiesCollector) при первом
        //    encode конкретного типа — synchronized lookup. После прогрева — lock-free.
        builder.allowBlockingCallsInside("com.fasterxml.jackson.databind.ObjectMapper", "_writeValueAndClose");
        builder.allowBlockingCallsInside("com.fasterxml.jackson.databind.ObjectMapper", "writeValueAsBytes");

        // 3. JDK security/crypto: SecureRandom при первой инициализации может
        //    блокировать на /dev/random в Linux. Используется в UUID.randomUUID() при генерации correlationId.
        builder.allowBlockingCallsInside("java.security.SecureRandom", "<init>");
        builder.allowBlockingCallsInside("sun.security.provider.NativePRNG$RandomIO", "implNextBytes");

        // 4. Java 21 ScopedValue infrastructure внутри Reactor Hooks — internal,
        //    cold-path при первом вызове ContextSnapshotFactory.
        builder.allowBlockingCallsInside(
                "io.micrometer.context.ContextSnapshotFactoryHelper",
                "captureFromContext");
    }
}
