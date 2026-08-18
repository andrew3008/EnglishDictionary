package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.Set;

public final class AgentEventCodes {

    public static final String BOOT_INSTALLED = "PTA-BOOT-001";
    public static final String BOOT_CLASSIFIED_FAILURE = "PTA-BOOT-002";
    public static final String BOOT_HOSTNAME_TIMEOUT = "PTA-BOOT-003";
    public static final String BOOT_HOSTNAME_FAILED = "PTA-BOOT-004";
    public static final String BOOT_RESOURCE_IDENTITY = "PTA-BOOT-005";
    public static final String BOOT_MANIFEST_VERSION = "PTA-BOOT-006";
    public static final String BOOT_FORBIDDEN_SPI = "PTA-BOOT-007";
    public static final String BOOT_PROCESSOR_REGISTRATION = "PTA-BOOT-008";
    public static final String BOOT_UPSTREAM_SERVICE = "PTA-BOOT-009";
    public static final String BOOT_SPI_ATTESTATION_EVIDENCE = "PTA-BOOT-010";

    public static final String VALID_UNRECOGNIZED_MODE = "PTA-VALID-001";
    public static final String VALID_POLICY = "PTA-VALID-002";
    public static final String VALID_MISSING_ATTRIBUTES = "PTA-VALID-003";

    public static final String SCRUB_HMAC_MISSING = "PTA-SCRUB-001";
    public static final String SCRUB_RULE_FAILED = "PTA-SCRUB-002";
    public static final String SCRUB_UNSUPPORTED_TYPE = "PTA-SCRUB-003";
    public static final String SCRUB_BOUNDARY_COLLAPSE = "PTA-SCRUB-004";
    public static final String SCRUB_PRIORITY_FLOOR = "PTA-SCRUB-005";
    public static final String SCRUB_CIRCUIT_OPEN = "PTA-SCRUB-006";
    public static final String SCRUB_RULES_UNREADABLE = "PTA-SCRUB-007";
    public static final String SCRUB_INITIALIZED = "PTA-SCRUB-008";
    public static final String SCRUB_PROVIDER_FAILED = "PTA-SCRUB-009";
    public static final String SCRUB_EXTENSION_SKIPPED = "PTA-SCRUB-010";
    public static final String SCRUB_UNKNOWN_BUILTIN = "PTA-SCRUB-011";
    public static final String SCRUB_EXPORT_BOUNDARY = "PTA-SCRUB-012";
    public static final String SCRUB_FAILURE = "PTA-SCRUB-013";

    public static final String EXPORT_SAFE_EXPORTER = "PTA-EXPORT-001";
    public static final String EXPORT_DROP_OLDEST = "PTA-EXPORT-002";
    public static final String EXPORT_WORKER = "PTA-EXPORT-003";
    public static final String EXPORT_TIMED = "PTA-EXPORT-004";
    public static final String EXPORT_LIFECYCLE = "PTA-EXPORT-005";
    public static final String EXPORT_CALLBACK = "PTA-EXPORT-006";
    public static final String EXPORT_COMPOSITE_LIFECYCLE = "PTA-EXPORT-007";
    public static final String EXPORT_WATCHDOG = "PTA-EXPORT-008";
    public static final String EXPORT_FACTORY = "PTA-EXPORT-009";

    public static final String SAMPLE_FALLBACK = "PTA-SAMPLE-001";

    public static final String CTRL_PLANE = "PTA-CTRL-001";
    public static final String CTRL_WRITER = "PTA-CTRL-002";
    public static final String CTRL_UPDATE = "PTA-CTRL-003";
    public static final String CTRL_MBEAN_BATCH = "PTA-CTRL-004";

    public static final String RATE_THROTTLED = "PTA-RATE-001";
    public static final String RATE_RECOVERY = "PTA-RATE-002";

    public static final String ENCODE_FALLBACK = "PTA-ENCODE-001";

    private static final Set<String> KNOWN_CODES = Set.of(
            BOOT_INSTALLED,
            BOOT_CLASSIFIED_FAILURE,
            BOOT_HOSTNAME_TIMEOUT,
            BOOT_HOSTNAME_FAILED,
            BOOT_RESOURCE_IDENTITY,
            BOOT_MANIFEST_VERSION,
            BOOT_FORBIDDEN_SPI,
            BOOT_PROCESSOR_REGISTRATION,
            BOOT_UPSTREAM_SERVICE,
            BOOT_SPI_ATTESTATION_EVIDENCE,
            VALID_UNRECOGNIZED_MODE,
            VALID_POLICY,
            VALID_MISSING_ATTRIBUTES,
            SCRUB_HMAC_MISSING,
            SCRUB_RULE_FAILED,
            SCRUB_UNSUPPORTED_TYPE,
            SCRUB_BOUNDARY_COLLAPSE,
            SCRUB_PRIORITY_FLOOR,
            SCRUB_CIRCUIT_OPEN,
            SCRUB_RULES_UNREADABLE,
            SCRUB_INITIALIZED,
            SCRUB_PROVIDER_FAILED,
            SCRUB_EXTENSION_SKIPPED,
            SCRUB_UNKNOWN_BUILTIN,
            SCRUB_EXPORT_BOUNDARY,
            SCRUB_FAILURE,
            EXPORT_SAFE_EXPORTER,
            EXPORT_DROP_OLDEST,
            EXPORT_WORKER,
            EXPORT_TIMED,
            EXPORT_LIFECYCLE,
            EXPORT_CALLBACK,
            EXPORT_COMPOSITE_LIFECYCLE,
            EXPORT_WATCHDOG,
            EXPORT_FACTORY,
            SAMPLE_FALLBACK,
            CTRL_PLANE,
            CTRL_WRITER,
            CTRL_UPDATE,
            CTRL_MBEAN_BATCH,
            RATE_THROTTLED,
            RATE_RECOVERY,
            ENCODE_FALLBACK
    );

    private AgentEventCodes() {
    }

    static boolean isKnown(String eventCode) {
        return (eventCode != null) && KNOWN_CODES.contains(eventCode);
    }

    static Set<String> knownCodes() {
        return KNOWN_CODES;
    }
}
