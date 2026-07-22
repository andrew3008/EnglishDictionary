package space.br1440.platform.tracing.otel.javaagent.configuration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.validation.ValidationSnapshot;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidationPolicyHolder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SP-02 — Strict validation production guardrail.
 * <p>
 * Verifies the two-signal model ({@code platform.environment} × {@code platform.tracing.validation.mode})
 * and the environment-safety guard:
 * <ul>
 *   <li>dev/staging → STRICT allowed</li>
 *   <li>production/UNKNOWN → STRICT downgraded to LENIENT at startup; runtime STRICT rejected</li>
 *   <li>DISABLED → always allowed, with risk-visibility WARN</li>
 *   <li>{@code deployment.environment.name} (OTel resource attribute) is NOT the guard signal</li>
 * </ul>
 */
class ValidationEnvironmentGuardTest {

    // -----------------------------------------------------------------------------------------
    // Startup mode resolution
    // -----------------------------------------------------------------------------------------

    @Nested
    class StartupModeResolution {

        /** Test 1 — dev + STRICT is allowed at startup. */
        @Test
        void devEnvironmentAllowsStrictValidation() {
            ValidationModeResolver.Resolution r =
                    ValidationModeResolver.resolve(PlatformEnvironment.DEV, ValidationMode.STRICT);

            assertThat(r.effectiveMode()).isEqualTo(ValidationMode.STRICT);
            assertThat(r.strictRuntimeAllowed()).isTrue();
        }

        /** Test 2 — staging + STRICT is allowed at startup. */
        @Test
        void stagingEnvironmentAllowsStrictValidation() {
            ValidationModeResolver.Resolution r =
                    ValidationModeResolver.resolve(PlatformEnvironment.STAGING, ValidationMode.STRICT);

            assertThat(r.effectiveMode()).isEqualTo(ValidationMode.STRICT);
            assertThat(r.strictRuntimeAllowed()).isTrue();
        }

        /** Test 3 — production + STRICT is downgraded to LENIENT at startup. */
        @Test
        void productionEnvironmentDowngradesStrictValidationToLenient() {
            ValidationModeResolver.Resolution r =
                    ValidationModeResolver.resolve(PlatformEnvironment.PRODUCTION, ValidationMode.STRICT);

            assertThat(r.effectiveMode())
                    .as("STRICT must be downgraded to LENIENT in production")
                    .isEqualTo(ValidationMode.LENIENT);
            assertThat(r.strictRuntimeAllowed())
                    .as("Runtime STRICT updates must also be rejected in production")
                    .isFalse();
        }

        /**
         * Test 4 — UNKNOWN environment + STRICT is downgraded to LENIENT at startup.
         * Covers: platform.environment missing, blank, and unrecognized.
         */
        @Test
        void unknownEnvironmentDowngradesStrictValidationToLenient() {
            // missing
            assertUnknownDowngradesStrict(PlatformEnvironment.parse(null));
            // blank
            assertUnknownDowngradesStrict(PlatformEnvironment.parse("   "));
            // unrecognized value
            assertUnknownDowngradesStrict(PlatformEnvironment.parse("preprod"));
            // explicitly UNKNOWN enum
            assertUnknownDowngradesStrict(PlatformEnvironment.UNKNOWN);
        }

        private static void assertUnknownDowngradesStrict(PlatformEnvironment env) {
            assertThat(env).isEqualTo(PlatformEnvironment.UNKNOWN);

            ValidationModeResolver.Resolution r =
                    ValidationModeResolver.resolve(env, ValidationMode.STRICT);

            assertThat(r.effectiveMode())
                    .as("STRICT must be downgraded to LENIENT when environment is UNKNOWN")
                    .isEqualTo(ValidationMode.LENIENT);
            assertThat(r.strictRuntimeAllowed()).isFalse();
        }

        /** Test 9 — production + DISABLED is allowed (risk-visibility WARN expected but not asserted). */
        @Test
        void productionEnvironmentAllowsDisabledValidationWithRiskVisibility() {
            ValidationModeResolver.Resolution r =
                    ValidationModeResolver.resolve(PlatformEnvironment.PRODUCTION, ValidationMode.DISABLED);

            assertThat(r.effectiveMode())
                    .as("DISABLED must be allowed in production (risk-visible)")
                    .isEqualTo(ValidationMode.DISABLED);
            // DISABLED means strict flag is irrelevant; strictRuntimeAllowed=false for production
            assertThat(r.strictRuntimeAllowed()).isFalse();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Runtime update policy
    // -----------------------------------------------------------------------------------------

    @Nested
    class RuntimeUpdatePolicy {

        /** Test 5 — production runtime update LENIENT → STRICT is rejected. */
        @Test
        void productionEnvironmentRejectsRuntimeStrictUpdate() {
            // Startup: production + LENIENT → effectiveMode=LENIENT, strictRuntimeAllowed=false
            ValidationModeResolver.Resolution startup =
                    ValidationModeResolver.resolve(PlatformEnvironment.PRODUCTION, ValidationMode.LENIENT);

            ValidationPolicyHolder holder = new ValidationPolicyHolder(
                    ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "startup"),
                    startup.strictRuntimeAllowed());  // false for production

            boolean applied = holder.tryApplyPolicyUpdate(true, true, "runtime-update");

            assertThat(applied).as("Runtime STRICT update must be rejected in production").isFalse();
            assertThat(holder.current().strict()).as("Policy must remain LENIENT").isFalse();
            assertThat(holder.version()).as("Version must not increment on rejection").isEqualTo(1);
        }

        /** Test 6 — UNKNOWN runtime update LENIENT → STRICT is rejected. */
        @Test
        void unknownEnvironmentRejectsRuntimeStrictUpdate() {
            // Startup: UNKNOWN + LENIENT → effectiveMode=LENIENT, strictRuntimeAllowed=false
            ValidationModeResolver.Resolution startup =
                    ValidationModeResolver.resolve(PlatformEnvironment.UNKNOWN, ValidationMode.LENIENT);

            ValidationPolicyHolder holder = new ValidationPolicyHolder(
                    ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "startup"),
                    startup.strictRuntimeAllowed());  // false for UNKNOWN

            boolean applied = holder.tryApplyPolicyUpdate(true, true, "runtime-update");

            assertThat(applied).as("Runtime STRICT update must be rejected when environment is UNKNOWN").isFalse();
            assertThat(holder.current().strict()).isFalse();
            assertThat(holder.version()).isEqualTo(1);
        }

        /** Test 7 — dev/staging runtime update LENIENT → STRICT is allowed. */
        @Test
        void nonProductionEnvironmentAllowsRuntimeStrictUpdate() {
            for (PlatformEnvironment env : new PlatformEnvironment[]{PlatformEnvironment.DEV, PlatformEnvironment.STAGING}) {
                ValidationModeResolver.Resolution startup =
                        ValidationModeResolver.resolve(env, ValidationMode.LENIENT);

                ValidationPolicyHolder holder = new ValidationPolicyHolder(
                        ValidationSnapshot.fromPolicy(true, false, 1, Instant.now(), "startup"),
                        startup.strictRuntimeAllowed());  // true for dev/staging

                boolean applied = holder.tryApplyPolicyUpdate(true, true, "runtime-update");

                assertThat(applied).as("Runtime STRICT update must be allowed in " + env).isTrue();
                assertThat(holder.current().strict())
                        .as("Policy must become STRICT after update in " + env)
                        .isTrue();
                assertThat(holder.version()).isEqualTo(2);
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // deployment.environment.name guard exclusion
    // -----------------------------------------------------------------------------------------

    /**
     * Test 8 — {@code deployment.environment.name} does NOT allow STRICT when
     * {@code platform.environment} is UNKNOWN.
     * <p>
     * Even if the OTel resource attribute {@code deployment.environment.name} is set to
     * {@code staging} or {@code dev}, the control-plane environment signal is derived solely
     * from {@code platform.environment}. The resolver accepts only {@link PlatformEnvironment},
     * which is parsed exclusively from {@code platform.environment} — it has no knowledge of
     * {@code deployment.environment.name}.
     */
    @Test
    void deploymentEnvironmentNameDoesNotAllowStrictWhenPlatformEnvironmentIsUnknown() {
        // Scenario: platform.environment is missing (UNKNOWN), but in production the OTel
        // resource attribute deployment.environment.name might be "staging" or "dev".
        // The resolver must NOT consult that attribute — it only knows PlatformEnvironment.
        PlatformEnvironment controlPlaneEnv = PlatformEnvironment.parse(null); // → UNKNOWN
        assertThat(controlPlaneEnv).isEqualTo(PlatformEnvironment.UNKNOWN);

        // STRICT requested — must be downgraded because control-plane env is UNKNOWN,
        // regardless of any telemetry attribute value.
        ValidationModeResolver.Resolution r =
                ValidationModeResolver.resolve(controlPlaneEnv, ValidationMode.STRICT);

        assertThat(r.effectiveMode())
                .as("STRICT must be downgraded even when deployment.environment.name might be staging/dev — "
                        + "only platform.environment is the control-plane guard")
                .isEqualTo(ValidationMode.LENIENT);
        assertThat(r.strictRuntimeAllowed()).isFalse();

        // The resolver implementation proof: ValidationModeResolver.resolve() accepts
        // PlatformEnvironment (from platform.environment only) and has no access to
        // PlatformAttributes.PLATFORM_ENVIRONMENT ("deployment.environment.name").
    }

    // -----------------------------------------------------------------------------------------
    // PlatformEnvironment parsing
    // -----------------------------------------------------------------------------------------

    @Nested
    class EnvironmentParsing {

        @Test
        void parsesProductionAliases() {
            assertThat(PlatformEnvironment.parse("production")).isEqualTo(PlatformEnvironment.PRODUCTION);
            assertThat(PlatformEnvironment.parse("PRODUCTION")).isEqualTo(PlatformEnvironment.PRODUCTION);
            assertThat(PlatformEnvironment.parse("prod")).isEqualTo(PlatformEnvironment.PRODUCTION);
        }

        @Test
        void parsesStagingAliases() {
            assertThat(PlatformEnvironment.parse("staging")).isEqualTo(PlatformEnvironment.STAGING);
            assertThat(PlatformEnvironment.parse("STAGING")).isEqualTo(PlatformEnvironment.STAGING);
            assertThat(PlatformEnvironment.parse("stage")).isEqualTo(PlatformEnvironment.STAGING);
        }

        @Test
        void parsesDevAliases() {
            assertThat(PlatformEnvironment.parse("dev")).isEqualTo(PlatformEnvironment.DEV);
            assertThat(PlatformEnvironment.parse("DEV")).isEqualTo(PlatformEnvironment.DEV);
            assertThat(PlatformEnvironment.parse("development")).isEqualTo(PlatformEnvironment.DEV);
        }

        @Test
        void unknownValuesResolveToUnknown() {
            assertThat(PlatformEnvironment.parse(null)).isEqualTo(PlatformEnvironment.UNKNOWN);
            assertThat(PlatformEnvironment.parse("")).isEqualTo(PlatformEnvironment.UNKNOWN);
            assertThat(PlatformEnvironment.parse("  ")).isEqualTo(PlatformEnvironment.UNKNOWN);
            assertThat(PlatformEnvironment.parse("preprod")).isEqualTo(PlatformEnvironment.UNKNOWN);
            assertThat(PlatformEnvironment.parse("test")).isEqualTo(PlatformEnvironment.UNKNOWN);
        }
    }

    // -----------------------------------------------------------------------------------------
    // ValidationMode parsing
    // -----------------------------------------------------------------------------------------

    @Nested
    class ValidationModeParsing {

        @Test
        void parsesCaseInsensitive() {
            assertThat(ValidationMode.parse("LENIENT")).isEqualTo(ValidationMode.LENIENT);
            assertThat(ValidationMode.parse("lenient")).isEqualTo(ValidationMode.LENIENT);
            assertThat(ValidationMode.parse("STRICT")).isEqualTo(ValidationMode.STRICT);
            assertThat(ValidationMode.parse("strict")).isEqualTo(ValidationMode.STRICT);
            assertThat(ValidationMode.parse("DISABLED")).isEqualTo(ValidationMode.DISABLED);
            assertThat(ValidationMode.parse("disabled")).isEqualTo(ValidationMode.DISABLED);
        }

        @Test
        void unknownModeDefaultsToLenient() {
            assertThat(ValidationMode.parse(null)).isEqualTo(ValidationMode.LENIENT);
            assertThat(ValidationMode.parse("")).isEqualTo(ValidationMode.LENIENT);
            assertThat(ValidationMode.parse("WARN")).isEqualTo(ValidationMode.LENIENT);
        }
    }
}
