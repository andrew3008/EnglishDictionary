package space.br1440.platform.tracing.test.junit.internal;

/**
 * Режим жизненного цикла {@link io.opentelemetry.sdk.OpenTelemetrySdk} в тестах.
 * <p>
 * Соответствует трём фабричным методам {@code OtelSdkExtension}:
 * <ul>
 *   <li>{@link #METHOD} — {@code OtelSdkExtension.methodScope()}: SDK создаётся в Store метода и
 *       закрывается JUnit'ом после каждого теста; {@code exporter.reset()} не нужен.</li>
 *   <li>{@link #CLASS} — {@code OtelSdkExtension.classScope()}: SDK создаётся в Store
 *       корневого тест-класса; {@code @Nested}-блоки переиспользуют тот же объект; перед каждым
 *       тестом вызывается {@code exporter.reset()} для изоляции между методами.</li>
 *   <li>{@link #SHARED_NESTED} — {@code OtelSdkExtension.sharedAcrossNested()}: как {@link #CLASS},
 *       но без {@code reset()} — span'ы накапливаются между методами и {@code @Nested}-блоками.</li>
 * </ul>
 */
public enum ScopeMode {

    /** SDK создаётся и закрывается на каждый тест-метод. */
    METHOD,

    /** SDK создаётся один на корневой класс; перед каждым тестом — {@code exporter.reset()}. */
    CLASS,

    /** SDK создаётся один на корневой класс; {@code reset()} не вызывается. */
    SHARED_NESTED;

    public boolean isClassScoped() {
        return (this == CLASS || this == SHARED_NESTED);
    }

    public boolean resetsBetweenTests() {
        return (this == CLASS);
    }
}
