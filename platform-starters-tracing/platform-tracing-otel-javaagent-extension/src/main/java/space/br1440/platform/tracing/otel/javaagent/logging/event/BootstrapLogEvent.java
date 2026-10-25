package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.noCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventDefinition.requiresCause;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.ERROR;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.INFO;
import static space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel.WARN;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public enum BootstrapLogEvent implements AgentLogEventType {

    REMOTE_JMX_MUTATION_REGISTRATION_DENIED(
            noCause(
                    AgentEventCode.BOOT_REMOTE_JMX_DENIED, ERROR,
                    "Remote JMX mutation registration denied",
                    AgentLogField.FAILURE_CODE, AgentLogField.REMEDIATION, AgentLogField.OFFENDING_ARGUMENTS
            )
    ),

    EXTENSION_INSTALLED(
            noCause(
                    AgentEventCode.BOOT_INSTALLED, INFO,
                    "Platform tracing extension installed",
                    AgentLogField.LIFECYCLE, AgentLogField.PROFILE, AgentLogField.PROTOCOL_VERSION,
                    AgentLogField.EXTENSION_VERSION
            )
    ),

    BOOTSTRAP_PHASE_FAILED(
            requiresCause(
                    AgentEventCode.BOOT_CLASSIFIED_FAILURE, WARN,
                    "Bootstrap phase failed",
                    AgentLogField.FAILURE_CODE, AgentLogField.PHASE, AgentLogField.SUBPHASE,
                    AgentLogField.PROPERTY_NAME, AgentLogField.RESOURCE_LABEL
            )
    ),

    BOOTSTRAP_OPERATION_REJECTED(
            requiresCause(
                    AgentEventCode.BOOT_CLASSIFIED_FAILURE, WARN,
                    "Bootstrap operation rejected",
                    AgentLogField.FAILURE_CODE, AgentLogField.PHASE, AgentLogField.SUBPHASE,
                    AgentLogField.PROPERTY_NAME
            )
    ),

    UNKNOWN_AUTO_CONFIGURATION_CUSTOMIZER_PROVIDER_FORBIDDEN(
            noCause(
                    AgentEventCode.BOOT_FORBIDDEN_SPI, ERROR,
                    "Unknown AutoConfigurationCustomizerProvider is not in the allowed upstream set",
                    AgentLogField.NAME
            )
    ),

    SDK_TRACER_PROVIDER_CONFIGURER_FORBIDDEN(
            noCause(
                    AgentEventCode.BOOT_FORBIDDEN_SPI, ERROR,
                    "SdkTracerProviderConfigurer is forbidden",
                    AgentLogField.NAME
            )
    ),

    SPAN_PROCESSOR_REGISTRATION_FAILED(
            requiresCause(
                    AgentEventCode.BOOT_PROCESSOR_REGISTRATION, ERROR,
                    "Span processor registration failed"
            )
    ),

    EXTENSION_BUILD_METADATA_MISSING(
            noCause(
                    AgentEventCode.BOOT_ARTIFACT_VERSION, WARN,
                    "Extension build metadata is missing",
                    AgentLogField.REASON
            )
    ),

    EXTENSION_BUILD_METADATA_UNREADABLE(
            noCause(
                    AgentEventCode.BOOT_ARTIFACT_VERSION, WARN,
                    "Extension build metadata is unreadable",
                    AgentLogField.RESOURCE
            )
    ),

    EXTENSION_BUILD_METADATA_INVALID(
            noCause(
                    AgentEventCode.BOOT_ARTIFACT_VERSION, WARN,
                    "Extension build metadata is invalid",
                    AgentLogField.RESOURCE
            )
    );

    private final AgentLogEventDefinition definition;

    BootstrapLogEvent(AgentLogEventDefinition definition) {
        this.definition = definition;
    }

    @Override
    public AgentLogEventDefinition definition() {
        return definition;
    }
}
