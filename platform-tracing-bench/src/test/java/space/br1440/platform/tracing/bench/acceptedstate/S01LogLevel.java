package space.br1440.platform.tracing.bench.acceptedstate;

/**
 * S-01 corrective evidence. Closed log-level enum for the eighth Category A leaf
 * {@code diagnostics.log-level}.
 * <p>
 * Mirrors the constant set of the current production owner
 * {@code PlatformLogControl.LogLevel} ({@code OFF|ERROR|WARN|INFO|DEBUG|TRACE}) so that the
 * eight-leaf contract proof reasons about the same closed vocabulary the accepted envelope will have
 * to carry. Closure matters for deep immutability: an enum constant is a safely published immutable
 * singleton, so the policy record needs no defensive copy for this field.
 * <p>
 * Evidence-only. {@code PROPOSED} name; not an approved production type.
 */
enum S01LogLevel {
    OFF,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE
}
