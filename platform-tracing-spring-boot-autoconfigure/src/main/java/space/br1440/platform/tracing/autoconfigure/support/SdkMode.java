package space.br1440.platform.tracing.autoconfigure.support;

/**
 * Production-режим платформенного starter'а относительно OpenTelemetry SDK.
 * <p>
 * Starter никогда не создаёт и не принимает application-owned SDK. В режиме {@link #AGENT}
 * единственным владельцем SDK является Controlled Platform Agent Distribution. Режим
 * {@link #DISABLED} допустим только при полном отсутствии Agent и application runtime.
 *
 * <ul>
 *   <li>{@link #AGENT} — активен совместимый Controlled Platform Agent с полным READY-профилем;</li>
 *   <li>{@link #DISABLED} — tracing намеренно отключён; единственный успешный NoOp-режим.</li>
 * </ul>
 */
public enum SdkMode {
    AGENT,
    DISABLED
}
