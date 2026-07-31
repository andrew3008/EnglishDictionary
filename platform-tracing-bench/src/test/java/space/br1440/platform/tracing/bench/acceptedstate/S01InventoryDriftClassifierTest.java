package space.br1440.platform.tracing.bench.acceptedstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Fixture proofs for rename-resilience, same-role cardinality, and semantic violations.
 * Does not rename production code.
 */
@DisplayName("S-01 inventory drift classifier fixtures")
class S01InventoryDriftClassifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sameSemanticRoleChangedFqcn_isRenameOrMoveOnly() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        replaceSiteClass(after, "LegacyTracingDiagnosticsEndpoint", "RenamedTracingDiagnosticsEndpoint");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .isEqualTo(S01InventoryDriftClassifier.Kind.RENAME_OR_MOVE_ONLY);
    }

    @Test
    void sameSemanticRoleChangedMethod_isRenameOrMoveOnly() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        replaceSiteMethod(after, "LegacyTracingDiagnosticsEndpoint", "tracing()", "diagnostics()");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .isEqualTo(S01InventoryDriftClassifier.Kind.RENAME_OR_MOVE_ONLY);
    }

    @Test
    void lineNumberOnlyMethodChange_isRenameOrMoveOnly() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        replaceSiteMethod(after, "SafeSpanExporter", "setExportEnabled()", "setExportEnabled() line 999");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .isEqualTo(S01InventoryDriftClassifier.Kind.RENAME_OR_MOVE_ONLY);
    }

    @Test
    void sameRoleSecondWriterSite_isNewWriter_notRenameOnly() {
        // Restored original defect fixture: same WRITER role, second physical site.
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        ObjectNode site = after.withArray("accessSites").addObject();
        site.put("owner", "SafeSpanExporter");
        site.put("class", "RogueWriter");
        site.put("method", "mutate()");
        site.put("role", "WRITER");
        site.put("module", "platform-tracing-otel-javaagent-extension");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .as("same-role second physical WRITER must not be rename-equivalent")
                .isEqualTo(S01InventoryDriftClassifier.Kind.NEW_WRITER)
                .isNotEqualTo(S01InventoryDriftClassifier.Kind.RENAME_OR_MOVE_ONLY);
    }

    @Test
    void sameRoleSecondDeliveryTrigger_isNewWriter_notRenameOnly() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        ObjectNode site = after.withArray("accessSites").addObject();
        site.put("owner", "SafeSpanExporter");
        site.put("class", "SecondConfigServerAdapter");
        site.put("method", "onEnvironmentChange");
        site.put("role", "WRITER_DELIVERY_TRIGGER");
        site.put("module", "platform-tracing-spring-boot-autoconfigure");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .isEqualTo(S01InventoryDriftClassifier.Kind.NEW_WRITER)
                .isNotEqualTo(S01InventoryDriftClassifier.Kind.RENAME_OR_MOVE_ONLY);
    }

    @Test
    void removeOneOfMultipleWriterSites_isNotRenameOnly() {
        ObjectNode before = baseCorrective();
        // Start from a two-WRITER topology, then drop one.
        ObjectNode withTwo = baseCorrective();
        ObjectNode extra = withTwo.withArray("accessSites").addObject();
        extra.put("owner", "SafeSpanExporter");
        extra.put("class", "PlatformExportControl");
        extra.put("method", "setExportEnabled()");
        extra.put("role", "WRITER");
        extra.put("module", "platform-tracing-otel-javaagent-extension");

        ObjectNode after = baseCorrective(); // back to one WRITER

        assertThat(S01InventoryDriftClassifier.classify(withTwo, after))
                .as("reducing same-role writer cardinality is not rename-only")
                .isEqualTo(S01InventoryDriftClassifier.Kind.UNKNOWN_SEMANTIC_DRIFT)
                .isNotEqualTo(S01InventoryDriftClassifier.Kind.RENAME_OR_MOVE_ONLY);
        assertThat(S01InventoryDriftClassifier.classify(before, withTwo))
                .isEqualTo(S01InventoryDriftClassifier.Kind.NEW_WRITER);
    }

    @Test
    void secondOwnerIntroduced_isNewOwner() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        ObjectNode owner = after.withArray("mutableOwners").addObject();
        owner.put("class", "ExtraAcceptedOwner");
        owner.put("isCurrentMutableProductionOwner", true);
        owner.put("migratesToAcceptedState", true);
        owner.putArray("categoryALeaves");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .isEqualTo(S01InventoryDriftClassifier.Kind.NEW_OWNER);
    }

    @Test
    void newDistinctWriterRole_isNewWriter() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        ObjectNode site = after.withArray("accessSites").addObject();
        site.put("owner", "SafeSpanExporter");
        site.put("class", "RogueWriter");
        site.put("method", "mutate()");
        site.put("role", "WRITER_ALTERNATE_PATH");
        site.put("module", "platform-tracing-otel-javaagent-extension");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .isEqualTo(S01InventoryDriftClassifier.Kind.NEW_WRITER);
    }

    @Test
    void forbiddenSpringCacheWriter_isForbiddenBypass() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        ObjectNode site = after.withArray("accessSites").addObject();
        site.put("owner", "SafeSpanExporter");
        site.put("class", "SpringAcceptedStateCache");
        site.put("method", "put()");
        site.put("role", "WRITER");
        site.put("module", "platform-tracing-spring-boot-autoconfigure");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .isEqualTo(S01InventoryDriftClassifier.Kind.FORBIDDEN_BYPASS);
    }

    @Test
    void requiredDeliveryWriterRemoved_isNotRenameOnly() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        removeSitesWithRole(after, "WRITER_DELIVERY_TRIGGER");

        S01InventoryDriftClassifier.Kind kind = S01InventoryDriftClassifier.classify(before, after);
        assertThat(kind)
                .isIn(
                        S01InventoryDriftClassifier.Kind.UNKNOWN_SEMANTIC_DRIFT,
                        S01InventoryDriftClassifier.Kind.REMOVED_READER)
                .isNotEqualTo(S01InventoryDriftClassifier.Kind.RENAME_OR_MOVE_ONLY);
    }

    @Test
    void newAllowedReader_isReportedNotOwnerViolation() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        ObjectNode site = after.withArray("accessSites").addObject();
        site.put("owner", "SafeSpanExporter");
        site.put("class", "ExtraMetricsReader");
        site.put("method", "read()");
        site.put("role", "READER_METRICS");
        site.put("module", "platform-tracing-otel-javaagent-extension");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .isEqualTo(S01InventoryDriftClassifier.Kind.NEW_ALLOWED_READER);
    }

    @Test
    void duplicateLeafClaimsAcrossOwners_isDuplicateOwner() {
        ObjectNode before = baseCorrective();
        ObjectNode after = baseCorrective();
        ArrayNode owners = after.withArray("mutableOwners");
        ObjectNode second = owners.addObject();
        second.put("class", "DuplicateLeafOwner");
        second.put("isCurrentMutableProductionOwner", true);
        second.put("migratesToAcceptedState", true);
        second.putArray("categoryALeaves").add("sampling.enabled");

        assertThat(S01InventoryDriftClassifier.classify(before, after))
                .isIn(
                        S01InventoryDriftClassifier.Kind.DUPLICATE_OWNER,
                        S01InventoryDriftClassifier.Kind.NEW_OWNER);
    }

    private static ObjectNode baseCorrective() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode owners = root.putArray("mutableOwners");
        ObjectNode exporter = owners.addObject();
        exporter.put("class", "SafeSpanExporter");
        exporter.put("isCurrentMutableProductionOwner", true);
        exporter.put("migratesToAcceptedState", true);
        exporter.putArray("categoryALeaves").add("exporter.enabled");

        ArrayNode sites = root.putArray("accessSites");
        addSite(sites, "SafeSpanExporter", "SafeSpanExporter", "<field>", "OWNER_FIELD",
                "platform-tracing-otel-javaagent-extension");
        addSite(sites, "SafeSpanExporter", "SafeSpanExporter", "export()", "READER_HOT",
                "platform-tracing-otel-javaagent-extension");
        addSite(sites, "SafeSpanExporter", "SafeSpanExporter", "setExportEnabled()", "WRITER",
                "platform-tracing-otel-javaagent-extension");
        addSite(sites, "SafeSpanExporter", "LegacyTracingDiagnosticsEndpoint", "tracing()", "READER_DELIVERY",
                "platform-tracing-spring-boot-autoconfigure");
        addSite(sites, "SafeSpanExporter", "ConfigServerTracingPolicyAdapter", "onEnvironmentChange",
                "WRITER_DELIVERY_TRIGGER", "platform-tracing-spring-boot-autoconfigure");
        return root;
    }

    private static void addSite(
            ArrayNode sites,
            String owner,
            String className,
            String method,
            String role,
            String module) {
        ObjectNode site = sites.addObject();
        site.put("owner", owner);
        site.put("class", className);
        site.put("method", method);
        site.put("role", role);
        site.put("module", module);
    }

    private static void replaceSiteClass(ObjectNode corrective, String from, String to) {
        corrective.get("accessSites").forEach(site -> {
            if (from.equals(site.path("class").asText())) {
                ((ObjectNode) site).put("class", to);
            }
        });
    }

    private static void replaceSiteMethod(ObjectNode corrective, String className, String from, String to) {
        corrective.get("accessSites").forEach(site -> {
            if (className.equals(site.path("class").asText())
                    && site.path("method").asText().contains(from.replace("()", ""))) {
                ((ObjectNode) site).put("method", to);
            }
        });
    }

    private static void removeSitesWithRole(ObjectNode corrective, String role) {
        ArrayNode sites = (ArrayNode) corrective.get("accessSites");
        ArrayNode kept = MAPPER.createArrayNode();
        sites.forEach(site -> {
            if (!role.equals(site.path("role").asText())) {
                kept.add(site);
            }
        });
        corrective.set("accessSites", kept);
    }
}
