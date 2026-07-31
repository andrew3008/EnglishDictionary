package space.br1440.platform.tracing.bench.acceptedstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Mandatory S-01 semantic architecture fitness for ordinary {@code test}/{@code check}.
 * <p>
 * Protects accepted-state ownership and delivery <em>roles</em>, not exact FQCNs of Spring
 * delivery adapters. Rename-only topology changes belong in
 * {@link S01OwnerInventoryDriftTest} (evidence/release task).
 */
@DisplayName("S-01 semantic architecture fitness (mandatory, rename-resistant)")
class S01SemanticArchitectureFitnessTest {

    private static final Path EVIDENCE = Paths.get(
            "docs", "research", "evidence", "accepted-state-primitive", "repository-symbol-inventory.json");

    private static final List<String> CATEGORY_A_LEAVES = List.of(
            "sampling.enabled",
            "sampling.ratio",
            "sampling.route-ratios",
            "validation.enabled",
            "validation.strict",
            "exporter.enabled",
            "propagation.trace-control-headers.enabled",
            "diagnostics.log-level");

    private static final List<String> REQUIRED_ACCESS_SITE_FIELDS = List.of(
            "module",
            "package",
            "class",
            "method",
            "stateField",
            "role",
            "currentMutationEntryPoint",
            "currentGate",
            "targetCategoryALeaf",
            "futureP1Replacement",
            "futureDeletionPhase",
            "zeroReferenceProof");

    private static final List<String> B1_OWNERS = List.of(
            "SafeSpanExporter",
            "PlatformPropagationGate",
            "PlatformLogControl");

    private static JsonNode corrective;

    @BeforeAll
    static void loadEvidence() throws IOException {
        Path file = repositoryRoot().resolve(EVIDENCE);
        assertThat(Files.isRegularFile(file))
                .as("committed evidence must exist at %s", file)
                .isTrue();
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        corrective = root.get("correctiveEvidence");
        assertThat(corrective)
                .as("repository-symbol-inventory.json must carry a correctiveEvidence section")
                .isNotNull();
    }

    @Test
    @DisplayName("accepted runtime envelope maps exactly eight Category A leaves")
    void acceptedEnvelopeMapsExactlyEightLeaves() {
        JsonNode leaves = corrective.get("acceptedRuntimeEnvelopeLeaves");
        List<String> names = new ArrayList<>();
        leaves.forEach(leaf -> names.add(leaf.get("leaf").asText()));

        assertThat(leaves.size()).isEqualTo(8);
        assertThat(names).containsExactlyElementsOf(CATEGORY_A_LEAVES);
    }

    @Test
    @DisplayName("every leaf names current owner fields and exactly one target accepted-state owner")
    void everyLeafHasCurrentAndSingleTargetOwner() {
        Set<String> targetOwners = new TreeSet<>();
        JsonNode leaves = corrective.get("acceptedRuntimeEnvelopeLeaves");

        leaves.forEach(leaf -> {
            String name = leaf.get("leaf").asText();
            assertNonBlank(leaf, "canonicalProperty", name);
            assertNonBlank(leaf, "currentOwnerClass", name);
            assertNonBlank(leaf, "currentOwnerStateField", name);
            assertNonBlank(leaf, "currentOwnerModule", name);
            assertNonBlank(leaf, "targetOwner", name);
            assertNonBlank(leaf, "targetPolicyField", name);
            targetOwners.add(leaf.get("targetOwner").asText());
        });
        assertThat(targetOwners)
                .as("E3 requires exactly one accepted-state owner for all eight leaves")
                .hasSize(1);
    }

    @Test
    @DisplayName("Category B scrubbing and drop/force leaves stay outside accepted runtime envelope")
    void categoryBIsExcluded() {
        List<String> mustBeExcluded = List.of(
                "scrubbing.enabled",
                "scrubbing.built-in-rules",
                "sampling.drop-paths",
                "sampling.force-record-values");

        List<String> excluded = new ArrayList<>();
        corrective.get("excludedFromAcceptedRuntimeEnvelope")
                .forEach(entry -> excluded.add(entry.get("leaf").asText()));
        List<String> envelopeLeaves = new ArrayList<>();
        corrective.get("acceptedRuntimeEnvelopeLeaves")
                .forEach(leaf -> envelopeLeaves.add(leaf.get("leaf").asText()));

        assertThat(excluded).containsAll(mustBeExcluded);
        assertThat(envelopeLeaves).doesNotContainAnyElementsOf(excluded);
        assertThat(envelopeLeaves)
                .as("no accepted leaf may mention scrubbing or a secret")
                .allSatisfy(leaf -> {
                    String lower = leaf.toLowerCase(Locale.ROOT);
                    assertThat(lower).doesNotContain("scrub");
                    assertThat(lower).doesNotContain("secret");
                });
        corrective.get("excludedFromAcceptedRuntimeEnvelope").forEach(entry -> {
            assertThat(entry.get("category").asText()).isEqualTo("B");
            assertNonBlank(entry, "reason", entry.get("leaf").asText());
            assertNonBlank(entry, "replacement", entry.get("leaf").asText());
        });
    }

    @Test
    @DisplayName("ScrubbingPolicyHolder Category B deletion remains recorded as completed")
    void scrubbingHolderDeletionCompleted() {
        JsonNode holder = ownerByClass("ScrubbingPolicyHolder");

        assertThat(holder).as("ScrubbingPolicyHolder must remain inventoried after deletion").isNotNull();
        assertThat(holder.get("migratesToAcceptedState").asBoolean()).isFalse();
        assertThat(holder.get("isCurrentMutableProductionOwner").asBoolean()).isFalse();
        assertThat(holder.get("categoryALeaves").size()).isZero();
        assertThat(holder.get("futureDeletionPhase").asText()).contains("COMPLETED");
        assertThat(holder.get("zeroReferenceProof").asText()).contains("0 files");
        assertNonBlank(holder, "replacement", "ScrubbingPolicyHolder");
        assertNonBlank(holder, "planCorrectionRequired", "ScrubbingPolicyHolder");
    }

    @Test
    @DisplayName("mutable owner migration map claims each Category A leaf exactly once")
    void ownerMigrationMapIsExact() {
        List<String> claimedLeaves = new ArrayList<>();
        JsonNode owners = corrective.get("mutableOwners");

        assertThat(owners.size()).isGreaterThanOrEqualTo(6);
        owners.forEach(owner -> {
            String name = owner.get("class").asText();
            assertNonBlank(owner, "module", name);
            assertNonBlank(owner, "package", name);
            assertNonBlank(owner, "stateField", name);
            assertNonBlank(owner, "replacement", name);
            assertNonBlank(owner, "futureDeletionPhase", name);
            assertNonBlank(owner, "zeroReferenceProof", name);
            owner.get("categoryALeaves").forEach(leaf -> claimedLeaves.add(leaf.asText()));
        });
        assertThat(claimedLeaves)
                .hasSize(8)
                .containsExactlyInAnyOrderElementsOf(CATEGORY_A_LEAVES);
    }

    @Test
    @DisplayName("every access site declares the required inventory schema fields")
    void accessSitesDeclareAllRequiredFields() {
        JsonNode sites = corrective.get("accessSites");
        assertThat(sites.size()).isGreaterThan(0);
        sites.forEach(site -> {
            String label = site.path("class").asText("?") + "." + site.path("method").asText("?");
            for (String field : REQUIRED_ACCESS_SITE_FIELDS) {
                assertNonBlank(site, field, label);
            }
        });
    }

    @Test
    @DisplayName("B1 owners retain OWNER_FIELD plus at least one reader and one writer role")
    void b1OwnersHaveCompleteAccessGraph() {
        JsonNode sites = corrective.get("accessSites");
        for (String owner : B1_OWNERS) {
            Set<String> roles = new HashSet<>();
            sites.forEach(site -> {
                if (site.get("owner").asText().equals(owner)) {
                    roles.add(site.get("role").asText());
                }
            });
            assertThat(roles)
                    .as("owner %s must have OWNER_FIELD, a READER* role and a WRITER* role", owner)
                    .contains("OWNER_FIELD")
                    .anySatisfy(role -> assertThat(role).startsWith("READER"))
                    .anySatisfy(role -> assertThat(role).startsWith("WRITER"));
        }
    }

    @Test
    @DisplayName("Spring delivery roles exist without pinning adapter FQCNs")
    void springDeliveryRolesPresentWithoutExactFqcn() {
        // Rename-resistant: protected capability is the role + module boundary, not the class name.
        assertRoleInModule("SafeSpanExporter", "READER_DELIVERY", "platform-tracing-spring-boot-autoconfigure");
        assertRoleInModule("SafeSpanExporter", "WRITER_DELIVERY_TRIGGER",
                "platform-tracing-spring-boot-autoconfigure");
        assertRoleInModule("PlatformPropagationGate", "WRITER_DELIVERY_TRIGGER",
                "platform-tracing-spring-boot-autoconfigure");
        assertRoleInModule("PlatformLogControl", "WRITER_DELIVERY_TRIGGER",
                "platform-tracing-spring-boot-autoconfigure");
        assertRoleInModule("SafeSpanExporter", "READER_INDIRECT", "platform-tracing-spring-boot-autoconfigure");
    }

    /**
     * Cardinality derived from committed corrective inventory (B1 owners):
     * {@code WRITER_DELIVERY_TRIGGER}=1, agent-side {@code WRITER}=2 (owner + Platform*Control facade).
     * A same-role duplicate increases these counts and fails ordinary {@code test}.
     */
    @Test
    @DisplayName("approved writer-role cardinalities are exact (same-role duplicates fail)")
    void approvedWriterRoleCardinalitiesAreExact() {
        for (String owner : B1_OWNERS) {
            assertRoleCardinality(owner, "WRITER_DELIVERY_TRIGGER", 1);
            assertRoleCardinality(owner, "WRITER", 2);
        }
    }

    @Test
    @DisplayName("verified absences remain explicit for B1 owners")
    void verifiedAbsencesAreRecorded() {
        JsonNode sites = corrective.get("accessSites");
        Set<String> unroutedOwners = new TreeSet<>();
        sites.forEach(site -> {
            if (site.get("role").asText().startsWith("NOT_")) {
                unroutedOwners.add(site.get("owner").asText());
            }
        });
        assertThat(unroutedOwners).contains(B1_OWNERS.toArray(new String[0]));
    }

    private static void assertRoleInModule(String owner, String role, String moduleFragment) {
        List<JsonNode> matches = new ArrayList<>();
        corrective.get("accessSites").forEach(site -> {
            if (site.get("owner").asText().equals(owner)
                    && site.get("role").asText().equals(role)
                    && site.get("module").asText().contains(moduleFragment)) {
                matches.add(site);
            }
        });
        assertThat(matches)
                .as("%s must retain role %s in module containing %s (class name may change)",
                        owner, role, moduleFragment)
                .isNotEmpty();
    }

    private static void assertRoleCardinality(String owner, String role, int expected) {
        List<JsonNode> matches = new ArrayList<>();
        corrective.get("accessSites").forEach(site -> {
            if (site.get("owner").asText().equals(owner)
                    && site.get("role").asText().equals(role)) {
                matches.add(site);
            }
        });
        assertThat(matches)
                .as("%s role %s must have exactly %d physical site(s); same-role duplicates are forbidden",
                        owner, role, expected)
                .hasSize(expected);
    }

    private static JsonNode ownerByClass(String className) {
        for (JsonNode owner : corrective.get("mutableOwners")) {
            if (owner.get("class").asText().equals(className)) {
                return owner;
            }
        }
        return null;
    }

    private static void assertNonBlank(JsonNode node, String field, String label) {
        JsonNode value = node.get(field);
        assertThat(value).as("%s must declare %s", label, field).isNotNull();
        assertThat(value.asText().isBlank()).as("%s.%s must not be blank", label, field).isFalse();
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new UncheckedIOException(new IOException(
                "repository root with settings.gradle not found above " + Paths.get("").toAbsolutePath()));
    }
}
