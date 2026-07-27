package space.br1440.platform.tracing.bench.acceptedstate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-01 corrective evidence for blockers B1 and B2: the committed owner inventory is complete and the
 * accepted runtime envelope maps exactly eight Category A leaves onto one target owner.
 * <p>
 * This test reads the committed machine-readable evidence rather than restating it in Java, so the
 * inventory has a single source of truth and an incomplete entry fails a gate instead of passing
 * review silently. It checks the shape and the completeness invariants; the repository facts
 * themselves were established by reading production source and are cited per entry.
 */
@DisplayName("S-01 corrective: committed owner inventory and eight-leaf migration map are complete")
class S01OwnerInventoryEvidenceTest {

    private static final Path EVIDENCE = Paths.get(
            "docs", "research", "evidence", "accepted-state-primitive", "repository-symbol-inventory.json");

    /** The binding Gate E5 leaf set. */
    private static final List<String> CATEGORY_A_LEAVES = List.of(
            "sampling.enabled",
            "sampling.ratio",
            "sampling.route-ratios",
            "validation.enabled",
            "validation.strict",
            "exporter.enabled",
            "propagation.trace-control-headers.enabled",
            "diagnostics.log-level");

    /** Fields every access-site entry must declare, per the B1 correction brief. */
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

    /** Owners whose absence from the original S-01 inventory was blocker B1. */
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
    @DisplayName("the accepted runtime envelope maps exactly the eight Category A leaves")
    void acceptedEnvelopeMapsExactlyEightLeaves() {
        // when
        JsonNode leaves = corrective.get("acceptedRuntimeEnvelopeLeaves");
        List<String> names = new ArrayList<>();
        leaves.forEach(leaf -> names.add(leaf.get("leaf").asText()));

        // then
        assertThat(leaves.size()).isEqualTo(8);
        assertThat(names).containsExactlyElementsOf(CATEGORY_A_LEAVES);
    }

    @Test
    @DisplayName("every leaf names a current owner with a state field and one single target owner")
    void everyLeafHasCurrentAndTargetOwner() {
        // when
        Set<String> targetOwners = new TreeSet<>();
        JsonNode leaves = corrective.get("acceptedRuntimeEnvelopeLeaves");

        // then
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
    @DisplayName("Category B scrubbing is excluded from the accepted runtime envelope")
    void categoryBIsExcluded() {
        // given
        List<String> mustBeExcluded = List.of(
                "scrubbing.enabled",
                "scrubbing.built-in-rules",
                "sampling.drop-paths",
                "sampling.force-record-values");

        // when
        List<String> excluded = new ArrayList<>();
        corrective.get("excludedFromAcceptedRuntimeEnvelope")
                .forEach(entry -> excluded.add(entry.get("leaf").asText()));
        List<String> envelopeLeaves = new ArrayList<>();
        corrective.get("acceptedRuntimeEnvelopeLeaves")
                .forEach(leaf -> envelopeLeaves.add(leaf.get("leaf").asText()));

        // then
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
    @DisplayName("ScrubbingPolicyHolder stays in the inventory as a Category B owner that does not migrate")
    void scrubbingHolderIsInventoriedButNotMigrated() {
        // when
        JsonNode holder = ownerByClass("ScrubbingPolicyHolder");

        // then
        assertThat(holder).as("ScrubbingPolicyHolder must not be hidden from the inventory").isNotNull();
        assertThat(holder.get("migratesToAcceptedState").asBoolean())
                .as("scrubbing is Category B and is not accepted runtime state")
                .isFalse();
        assertThat(holder.get("categoryALeaves").size()).isZero();
        assertNonBlank(holder, "replacement", "ScrubbingPolicyHolder");
        assertNonBlank(holder, "futureDeletionPhase", "ScrubbingPolicyHolder");
        assertNonBlank(holder, "planCorrectionRequired", "ScrubbingPolicyHolder");
    }

    @Test
    @DisplayName("every mutable owner declares a deletion or adaptation phase, and leaf claims are exact")
    void ownerMigrationMapIsExact() {
        // when
        List<String> claimedLeaves = new ArrayList<>();
        JsonNode owners = corrective.get("mutableOwners");

        // then
        assertThat(owners.size())
                .as("five Category A owners plus the Category B scrubbing owner")
                .isGreaterThanOrEqualTo(6);
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
                .as("each Category A leaf is claimed by exactly one current owner")
                .hasSize(8)
                .containsExactlyInAnyOrderElementsOf(CATEGORY_A_LEAVES);
    }

    @Test
    @DisplayName("every access site declares all twelve required inventory fields")
    void accessSitesDeclareAllRequiredFields() {
        // when
        JsonNode sites = corrective.get("accessSites");

        // then
        assertThat(sites.size()).isGreaterThan(0);
        sites.forEach(site -> {
            String label = site.path("class").asText("?") + "." + site.path("method").asText("?");
            for (String field : REQUIRED_ACCESS_SITE_FIELDS) {
                assertNonBlank(site, field, label);
            }
        });
    }

    @Test
    @DisplayName("the three owners missing from the original inventory now have owner, reader and writer sites")
    void b1OwnersHaveCompleteAccessGraph() {
        // when
        JsonNode sites = corrective.get("accessSites");

        // then
        for (String owner : B1_OWNERS) {
            Set<String> roles = new HashSet<>();
            sites.forEach(site -> {
                if (site.get("owner").asText().equals(owner)) {
                    roles.add(site.get("role").asText());
                }
            });
            assertThat(roles)
                    .as("owner %s must have an owning field site, at least one reader and at least one writer",
                            owner)
                    .contains("OWNER_FIELD")
                    .anySatisfy(role -> assertThat(role).startsWith("READER"))
                    .anySatisfy(role -> assertThat(role).startsWith("WRITER"));
        }
    }

    @Test
    @DisplayName("verified absences are recorded, not assumed: unrouted protocol paths are explicit")
    void verifiedAbsencesAreRecorded() {
        // when
        JsonNode sites = corrective.get("accessSites");
        Set<String> unroutedOwners = new TreeSet<>();
        sites.forEach(site -> {
            if (site.get("role").asText().startsWith("NOT_")) {
                unroutedOwners.add(site.get("owner").asText());
            }
        });

        // then
        assertThat(unroutedOwners)
                .as("the unified control protocol reaches none of the three owners; that absence is evidence")
                .contains(B1_OWNERS.toArray(new String[0]));
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

    /** Walks up from the working directory so the test runs from the root or the bench project. */
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
