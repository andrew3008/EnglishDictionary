package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import java.util.Objects;

/**
 * Нейтральное относительно загрузчиков свидетельство дистрибутива и topology агента.
 * В JMX payload из этого типа переносятся только строки, числа и boolean.
 *
 * <p>Каждое поле измеряется в рантайме. Поля, сравниваемые с константой сборки, здесь
 * не хранятся: такое сравнение ничего не проверяет, а потребителю readiness выглядит
 * как подтверждённый факт.
 */
public record ExtensionRuntimeEvidence(String agentDistributionSha256,
                                       String upstreamAgentVersion,
                                       String extensionVersion,
                                       String extensionDefiningLoader,
                                       String sdkDefiningLoader) {

    public ExtensionRuntimeEvidence {
        Objects.requireNonNull(agentDistributionSha256, "agentDistributionSha256");
        Objects.requireNonNull(upstreamAgentVersion, "upstreamAgentVersion");
        Objects.requireNonNull(extensionVersion, "extensionVersion");
        Objects.requireNonNull(extensionDefiningLoader, "extensionDefiningLoader");
        Objects.requireNonNull(sdkDefiningLoader, "sdkDefiningLoader");
    }

    public static ExtensionRuntimeEvidence unavailable(String extensionVersion) {
        return new ExtensionRuntimeEvidence(
                "",
                "",
                Objects.requireNonNull(extensionVersion, "extensionVersion"),
                "",
                ""
        );
    }

    public boolean complete() {
        return !agentDistributionSha256.isBlank()
                && !upstreamAgentVersion.isBlank()
                && !extensionVersion.isBlank()
                && !extensionDefiningLoader.isBlank()
                && !sdkDefiningLoader.isBlank();
    }
}
