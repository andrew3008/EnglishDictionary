package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class AgentLogEventCatalogTest {

    private static final Predicate<String> RESOURCE_IDENTITY_CODES = code -> code.startsWith("BOOT_RESOURCE_")
            || code.startsWith("BOOT_UPSTREAM_");

    private static final Map<Class<?>, Predicate<String>> CODE_DOMAINS = Map.ofEntries(
            Map.entry(BootstrapLogEvent.class, code -> code.startsWith("BOOT_") && !RESOURCE_IDENTITY_CODES.test(code)),
            Map.entry(ResourceIdentityLogEvent.class, RESOURCE_IDENTITY_CODES),
            Map.entry(ControlPlaneLogEvent.class, code -> code.startsWith("CTRL_")),
            Map.entry(ExporterLogEvent.class, code -> code.equals("EXPORT_SAFE_EXPORTER") || code.equals("EXPORT_TIMED")),
            Map.entry(ExportPipelineLogEvent.class, code -> code.startsWith("EXPORT_")
                    && !code.startsWith("EXPORT_SPAN_TIMEOUT")
                    && !code.equals("EXPORT_SAFE_EXPORTER")
                    && !code.equals("EXPORT_TIMED")),
            Map.entry(SpanTimeoutLogEvent.class, code -> code.startsWith("EXPORT_SPAN_TIMEOUT")),
            Map.entry(ScrubbingLogEvent.class, code -> code.startsWith("SCRUB_")),
            Map.entry(ValidationLogEvent.class, code -> code.startsWith("VALID_")),
            Map.entry(SamplingLogEvent.class, code -> code.startsWith("SAMPLE_")),
            Map.entry(LogThrottlingLogEvent.class, code -> code.startsWith("RATE_")));

    @Test
    void catalogContainsExactlyAllConstantsOfPermittedEnums() {
        List<Class<?>> permitted = List.of(AgentLogEventType.class.getPermittedSubclasses());

        assertThat(permitted).allMatch(Class::isEnum, "every AgentLogEventType implementation is an enum");
        List<AgentLogEventType> constants = permitted.stream()
                .flatMap(type -> Arrays.stream((AgentLogEventType[]) type.getEnumConstants()))
                .toList();
        assertThat(AgentLogEventCatalog.all()).containsExactlyInAnyOrderElementsOf(constants);
    }

    @Test
    void eventNamesAreUniqueAcrossDomains() {
        Map<String, List<AgentLogEventType>> byName = AgentLogEventCatalog.all().stream()
                .collect(Collectors.groupingBy(AgentLogEventType::name));

        assertThat(byName).allSatisfy((name, events) -> assertThat(events).as(name).hasSize(1));
    }

    @Test
    void bootstrap_phase_failure_allows_resource_label() {
        // given
        AgentLogEventType event = BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED;

        // when
        Set<AgentLogField> allowedFields = event.allowedFields();

        // then
        assertThat(AgentLogEventCatalog.all()).contains(event);
        assertThat(allowedFields).contains(AgentLogField.RESOURCE_LABEL);
    }

    @Test
    void everyDomainEnumOwnsOnlyItsEventCodes() {
        assertThat(CODE_DOMAINS.keySet())
                .containsExactlyInAnyOrder(AgentLogEventType.class.getPermittedSubclasses());

        assertThat(AgentLogEventCatalog.all()).allSatisfy(event -> assertThat(
                CODE_DOMAINS.get(event.getClass()).test(event.eventCode().name()))
                .as("%s.%s -> %s", event.getClass().getSimpleName(), event.name(), event.eventCode())
                .isTrue());
    }

    @Test
    void definitionsAreNotSharedBetweenEvents() {
        Set<AgentLogEventDefinition> definitions = Collections.newSetFromMap(new IdentityHashMap<>());

        AgentLogEventCatalog.all().forEach(event -> assertThat(definitions.add(event.definition()))
                .as(event.name())
                .isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventCode", "level", "message", "allowedFields"})
    void domainEnumsDoNotOverrideDefaultAccessors(String accessor) {
        for (Class<?> type : AgentLogEventType.class.getPermittedSubclasses()) {
            assertThatThrownBy(() -> type.getDeclaredMethod(accessor))
                    .as("%s must not override %s()", type.getSimpleName(), accessor)
                    .isInstanceOf(NoSuchMethodException.class);
        }
    }

    @Test
    void definitionRejectsInvalidMetadata() {
        assertThatThrownBy(() -> AgentLogEventDefinition.noCause(
                AgentEventCode.SCRUB_FAILURE, AgentLogLevel.WARN, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message must not be blank");

        assertThatThrownBy(() -> AgentLogEventDefinition.noCause(
                AgentEventCode.SCRUB_FAILURE, AgentLogLevel.WARN, "m", AgentLogField.NAME, AgentLogField.NAME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("allowedFields must not contain duplicates");

        assertThatThrownBy(() -> AgentLogEventDefinition.noCause(
                AgentEventCode.SCRUB_FAILURE, AgentLogLevel.WARN, "m", (AgentLogField) null))
                .isInstanceOf(NullPointerException.class);
    }

}
