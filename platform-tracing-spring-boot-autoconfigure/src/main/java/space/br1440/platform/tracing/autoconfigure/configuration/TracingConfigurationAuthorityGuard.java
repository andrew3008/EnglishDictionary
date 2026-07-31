package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Validates authority/posture combination at startup.
 * <p>
 * Fail-fast matrix:
 * <pre>
 *   HELM           + any posture  -> OK (no writer, no Actuator write surface)
 *   CONFIG_SERVER  + any posture  -> OK (no Actuator write surface)
 *   ACTUATOR       + LOCAL        -> OK (write surface present)
 *   ACTUATOR       + SHARED       -> FAIL-FAST
 *   ACTUATOR       + PRODUCTION   -> FAIL-FAST
 *   DISABLED       + CONFIG_SERVER -> FAIL-FAST
 *   DISABLED       + ACTUATOR      -> FAIL-FAST
 * </pre>
 * Note: platform.tracing.dev-control.enabled is removed.
 * authority=ACTUATOR is the sole activation signal for mutation surface.
 */
public final class TracingConfigurationAuthorityGuard {

    private static final Logger log = LoggerFactory.getLogger(TracingConfigurationAuthorityGuard.class);

    /**
     * Post-P6 Spring-only property names with no Agent-bootstrap replacement. {@code TracingProperties}
     * no longer binds them (ADR-spring-owns-only-what-spring-applies); presence in the Spring Environment
     * can only be leftover legacy YAML, so it fails startup with an exact-key, actionable message
     * instead of being silently ignored by {@code @ConfigurationProperties}.
     */
    private static final List<String> OBSOLETE_SPRING_ONLY_KEYS = List.of(
            "platform.tracing.log-level");

    /**
     * Exact property names the Java-agent extension ({@code ExtensionPropertyNames}, module
     * {@code platform-tracing-otel-javaagent-extension}, not importable here across the classloader
     * boundary) reads directly for its own bootstrap configuration. Spring does not bind or apply
     * these — {@code TracingProperties} deleted its sampling/validation mirrors post-P6 — but the two
     * planes may share one {@code application.yml}/environment, so presence alone is not an error.
     */
    private static final List<String> AGENT_BOOTSTRAP_KEEP_KEYS = List.of(
            "platform.tracing.sampling.enabled",
            "platform.tracing.sampling.ratio",
            "platform.tracing.sampling.route-ratios",
            "platform.tracing.sampling.force-record-header",
            "platform.tracing.sampling.qa-header",
            "platform.tracing.javaagent.sampling.drop-paths",
            "platform.tracing.javaagent.sampling.force-record-values",
            "platform.tracing.validation.enabled",
            "platform.tracing.validation.strict",
            "platform.tracing.validation.mode",
            "platform.tracing.validation.strict-runtime-allowed");

    private final TracingConfigurationAuthority authority;
    private final PlatformEnvironmentPosture posture;
    private final boolean tracingEnabled;

    public TracingConfigurationAuthorityGuard(
            TracingConfigurationAuthority authority,
            PlatformEnvironmentPosture posture,
            boolean tracingEnabled) {
        this.authority = authority;
        this.posture = posture;
        this.tracingEnabled = tracingEnabled;
    }

    public static TracingConfigurationAuthorityGuard from(Environment env) {
        failFastOnObsoleteSpringOnlyKeys(env);
        warnOnAgentBootstrapKeepKeysPresentInSpringEnvironment(env);
        String rawAuthority = env.getProperty("platform.tracing.configuration.authority", "").trim().toUpperCase();
        if (rawAuthority.isEmpty()) {
            throw new TracingConfigurationGuardException(
                    "Missing platform.tracing.configuration.authority. "
                            + "Exactly one of HELM, CONFIG_SERVER, ACTUATOR must be selected at startup; "
                            + "authority fallback is forbidden.");
        }
        TracingConfigurationAuthority authority;
        try {
            authority = TracingConfigurationAuthority.valueOf(rawAuthority);
        } catch (IllegalArgumentException e) {
            throw new TracingConfigurationGuardException(
                    "Invalid platform.tracing.configuration.authority='" + rawAuthority +
                    "'. Must be one of: HELM, CONFIG_SERVER, ACTUATOR");
        }
        PlatformEnvironmentPosture posture = PlatformEnvironmentPostureResolver.resolve(env);
        // Mode axis (platform.tracing.mode) and configuration.enabled both map to the
        // DISABLED half of the P4 mode/authority matrix. Either signal disables tracing.
        boolean modeDisabled = "DISABLED".equalsIgnoreCase(
                env.getProperty("platform.tracing.mode", "AGENT").trim());
        boolean configurationEnabled = env.getProperty(
                "platform.tracing.configuration.enabled", Boolean.class, true);
        boolean tracingEnabled = !modeDisabled && configurationEnabled;
        return new TracingConfigurationAuthorityGuard(authority, posture, tracingEnabled);
    }

    /**
     * Fails startup for exact Spring-only property names that {@code TracingProperties} no longer
     * binds and that have no Agent-bootstrap replacement. Deterministic replacement for the prior
     * silent-ignore behavior documented as a Phase 3 blocker in the post-P6-simplify audit.
     */
    private static void failFastOnObsoleteSpringOnlyKeys(Environment env) {
        for (String key : OBSOLETE_SPRING_ONLY_KEYS) {
            if (env.containsProperty(key)) {
                throw new TracingConfigurationGuardException(
                        "Obsolete Spring-only property '" + key + "' is set but is no longer bound or applied "
                                + "by Spring (TracingProperties deleted this mirror post-P6; there is no "
                                + "Agent-bootstrap replacement for this key). Remove it from configuration.");
            }
        }
    }

    /**
     * Warns, once per startup, when the Spring Environment carries Java-agent bootstrap-only
     * property names. These keys are read directly by the Agent extension, never by Spring; logging
     * their exact presence replaces the prior silent-success behavior that let operators believe
     * Spring YAML still configured sampling/validation policy.
     */
    private static void warnOnAgentBootstrapKeepKeysPresentInSpringEnvironment(Environment env) {
        List<String> present = new ArrayList<>();
        for (String key : AGENT_BOOTSTRAP_KEEP_KEYS) {
            if (env.containsProperty(key)) {
                present.add(key);
            }
        }
        if (!present.isEmpty()) {
            log.warn("Spring Environment contains Java-agent bootstrap-only propert{} {} that Spring does not "
                            + "bind or apply (TracingProperties has no sampling/validation mirror post-P6); "
                            + "these configure the Agent extension bootstrap channel only, not Spring.",
                    present.size() == 1 ? "y" : "ies", present);
        }
    }

    public TracingConfigurationAuthorityGuard validate() {
        if (!tracingEnabled) {
            if (authority == TracingConfigurationAuthority.CONFIG_SERVER) {
                throw new TracingConfigurationGuardException(
                        "Tracing disabled is incompatible with CONFIG_SERVER authority.");
            }
            if (authority == TracingConfigurationAuthority.ACTUATOR) {
                throw new TracingConfigurationGuardException(
                        "Tracing disabled is incompatible with ACTUATOR authority.");
            }
            log.info("Tracing disabled with HELM authority: no writer, no Actuator write surface.");
            return this;
        }
        if (authority == TracingConfigurationAuthority.ACTUATOR) {
            if (posture == PlatformEnvironmentPosture.SHARED) {
                throw new TracingConfigurationGuardException(
                        "ACTUATOR authority is forbidden in SHARED posture. Use CONFIG_SERVER or HELM.");
            }
            if (posture == PlatformEnvironmentPosture.PRODUCTION) {
                throw new TracingConfigurationGuardException(
                        "ACTUATOR authority is forbidden in PRODUCTION posture. Use CONFIG_SERVER or HELM.");
            }
        }
        return this;
    }

    public boolean isActuatorMutationSurfaceAllowed() {
        return tracingEnabled
                && authority == TracingConfigurationAuthority.ACTUATOR
                && posture == PlatformEnvironmentPosture.LOCAL;
    }

    public TracingConfigurationAuthority authority() { return authority; }
    public PlatformEnvironmentPosture posture() { return posture; }
    public boolean isTracingEnabled() { return tracingEnabled; }
}
