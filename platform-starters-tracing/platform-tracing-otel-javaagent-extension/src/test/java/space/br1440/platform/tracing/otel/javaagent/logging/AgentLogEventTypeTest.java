package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventCatalog;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExportPipelineLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SamplingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.CausePolicy;

class AgentLogEventTypeTest {

    private static final int MAX_FIELDS = 16;
    private static final int MAX_FIELD_NAME_CHARS = 64;
    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]*$");
    private static final Set<String> RESERVED_ROOT_NAMES = Set.of(
            "timestamp",
            "level",
            "logger",
            "message",
            "event_code",
            "event_name",
            "original_event_name",
            "fields",
            "error_type",
            "error_stacktrace",
            "stacktrace_truncated",
            "suppressed_count"
    );

    @Test
    void cause_policy_values_are_exactly_forbidden_optional_and_required() {
        assertThat(CausePolicy.values()).containsExactly(
                CausePolicy.FORBIDDEN,
                CausePolicy.OPTIONAL,
                CausePolicy.REQUIRED
        );
    }

    @Test
    void every_event_has_fixed_valid_metadata() {
        // given
        List<CatalogIdentity> identities = new ArrayList<>();

        // when
        for (AgentLogEventType eventType : AgentLogEventCatalog.all()) {
            identities.add(new CatalogIdentity(eventType.eventCode(), eventType.level(), eventType.message()));
        }

        // then
        assertThat(identities).doesNotHaveDuplicates();
        assertThat(AgentLogEventCatalog.all()).allSatisfy(eventType -> {
            assertThat(eventType.eventCode()).isNotNull();
            assertThat(eventType.level()).isNotNull().isNotEqualTo(AgentLogLevel.OFF);
            assertThat(eventType.message()).isNotBlank().doesNotContain("\n", "\r");
            assertThat(eventType.allowedFields()).hasSizeLessThanOrEqualTo(MAX_FIELDS);
            assertThat(eventType.definition().causePolicy()).isNotNull();
        });
    }

    @Test
    void field_catalog_has_valid_unique_names_outside_reserved_vocabularies() throws IOException {
        // given
        Set<String> ownerMaskingNames = new LinkedHashSet<>();
        for (String key : readOwnerApplyToKeys()) {
            ownerMaskingNames.add(canonicalName(key));
        }

        // when
        List<String> wireNames = new ArrayList<>();
        Set<String> canonicalWireNames = new LinkedHashSet<>();
        for (AgentLogField field : AgentLogField.values()) {
            wireNames.add(field.wireName());
            canonicalWireNames.add(canonicalName(field.wireName()));
        }

        // then
        assertThat(wireNames).doesNotHaveDuplicates();
        assertThat(AgentLogField.values()).allSatisfy(field -> {
            assertThat(field.wireName()).hasSizeLessThanOrEqualTo(MAX_FIELD_NAME_CHARS);
            assertThat(FIELD_NAME_PATTERN.matcher(field.wireName()).matches()).isTrue();
            assertThat(RESERVED_ROOT_NAMES).doesNotContain(field.wireName().toLowerCase(Locale.ROOT));
            assertThat(field.kind()).isNotNull();
        });
        assertThat(canonicalWireNames).doesNotContainAnyElementsOf(ownerMaskingNames);
    }

    @Test
    void only_expected_code_message_pairs_span_multiple_levels() {
        // given
        Map<CodeMessage, EnumSet<AgentLogLevel>> levelsByCodeMessage = new LinkedHashMap<>();

        // when
        for (AgentLogEventType eventType : AgentLogEventCatalog.all()) {
            CodeMessage key = new CodeMessage(eventType.eventCode(), eventType.message());
            levelsByCodeMessage.computeIfAbsent(key, ignored -> EnumSet.noneOf(AgentLogLevel.class))
                    .add(eventType.level());
        }
        Map<CodeMessage, EnumSet<AgentLogLevel>> multiLevel = new LinkedHashMap<>();
        levelsByCodeMessage.forEach((key, levels) -> {
            if (levels.size() > 1) {
                multiLevel.put(key, levels);
            }
        });

        // then
        CodeMessage rate = new CodeMessage(AgentEventCode.RATE_THROTTLED, "Agent logging is throttled");
        CodeMessage validation = new CodeMessage(
                AgentEventCode.VALID_MISSING_ATTRIBUTES,
                "Required span attributes are missing"
        );
        assertThat(multiLevel.keySet()).containsExactlyInAnyOrder(rate, validation);
        assertThat(multiLevel.get(rate)).containsExactlyInAnyOrder(AgentLogLevel.WARN, AgentLogLevel.ERROR);
        assertThat(multiLevel.get(validation)).containsExactlyInAnyOrder(AgentLogLevel.WARN, AgentLogLevel.ERROR);
    }

    @Test
    void optional_cause_policy_lists_known_events() {
        // given
        // when
        List<AgentLogEventType> optional = new ArrayList<>();
        for (AgentLogEventType eventType : AgentLogEventCatalog.all()) {
            if (eventType.definition().causePolicy() == CausePolicy.OPTIONAL) {
                optional.add(eventType);
            }
        }

        // then
        assertThat(optional).containsExactlyInAnyOrder(
                SamplingLogEvent.SAMPLER_FAILED_CLOSED_TO_DROP,
                ScrubbingLogEvent.EXTENSION_SCRUBBING_RULE_SKIPPED,
                ScrubbingLogEvent.SCRUBBING_RULES_UNREADABLE,
                ExportPipelineLogEvent.COMPOSITE_PROCESSOR_LIFECYCLE_FAILED
        );
    }

    @Test
    void blank_values_are_allowed_only_for_fields_with_valid_empty_state() {
        // given
        // when
        List<AgentLogField> blankAllowed = new ArrayList<>();
        for (AgentLogField field : AgentLogField.values()) {
            if (field.allowsBlank()) {
                blankAllowed.add(field);
            }
        }

        // then
        assertThat(blankAllowed).containsExactly(
                AgentLogField.CHANGED_LEAVES,
                AgentLogField.OFFENDING_ARGUMENTS
        );
        assertThat(AgentLogField.CHANGED_LEAVES.kind()).isEqualTo(AgentLogField.Kind.STRING);
        assertThat(AgentLogField.OFFENDING_ARGUMENTS.kind()).isEqualTo(AgentLogField.Kind.STRING);
    }

    @Test
    void catalog_covers_every_caller_event_code() {
        // given
        EnumSet<AgentEventCode> expected = EnumSet.allOf(AgentEventCode.class);
        expected.remove(AgentEventCode.ENCODE_FALLBACK);

        // when
        EnumSet<AgentEventCode> actual = EnumSet.noneOf(AgentEventCode.class);
        for (AgentLogEventType eventType : AgentLogEventCatalog.all()) {
            actual.add(eventType.eventCode());
        }

        // then
        assertThat(actual).isEqualTo(expected);
        assertThat(actual).allSatisfy(eventCode -> assertThat(eventCode.code()).isNotEqualTo("PTA-BOOT-011"));
    }

    @Test
    void allowed_fields_view_is_immutable() {
        // given
        Set<AgentLogField> allowedFields = BootstrapLogEvent.EXTENSION_INSTALLED.allowedFields();

        // when
        // then
        assertThatThrownBy(() -> allowedFields.remove(AgentLogField.LIFECYCLE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(BootstrapLogEvent.EXTENSION_INSTALLED.allowedFields()).containsExactlyInAnyOrder(
                AgentLogField.LIFECYCLE,
                AgentLogField.PROFILE,
                AgentLogField.PROTOCOL_VERSION,
                AgentLogField.EXTENSION_VERSION
        );
    }

    private static Set<String> readOwnerApplyToKeys() throws IOException {
        try (InputStream input = AgentLogEventTypeTest.class
                .getResourceAsStream("/platform-log-masking-rules.yaml")) {
            if (input == null) {
                throw new IOException("Owner masking YAML is not available on the test classpath");
            }

            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> keys = new LinkedHashSet<>();
            boolean inApplyToKeys = false;
            for (String line : yaml.split("\n", -1)) {
                String trimmed = line.stripTrailing();
                if (trimmed.endsWith("apply-to-keys:")) {
                    inApplyToKeys = true;
                    continue;
                }
                if (inApplyToKeys) {
                    String item = trimmed.strip();
                    if (item.startsWith("- ")) {
                        keys.add(item.substring(2).trim());
                        continue;
                    }
                    if (!item.isEmpty() && !item.startsWith("#")) {
                        inApplyToKeys = false;
                    }
                }
            }
            return Set.copyOf(keys);
        }
    }

    private static String canonicalName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(".", "");
    }

    private record CatalogIdentity(AgentEventCode eventCode, AgentLogLevel level, String message) {
    }

    private record CodeMessage(AgentEventCode eventCode, String message) {
    }
}
