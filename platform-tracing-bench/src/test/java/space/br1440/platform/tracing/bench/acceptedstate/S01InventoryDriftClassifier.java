package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Classifies S-01 corrective access-site inventory deltas for evidence/release review.
 * <p>
 * Semantic identity is {@code (owner, role)}. Physical identity is
 * {@code (module, class, method)} with line-number suffixes stripped from {@code method}.
 * Rename-only changes preserve semantic identity <em>and</em> per-role physical cardinality.
 * An additional physical site with the same owner+role increases cardinality and is not
 * rename-equivalent.
 */
final class S01InventoryDriftClassifier {

    enum Kind {
        RENAME_OR_MOVE_ONLY,
        NEW_ALLOWED_READER,
        REMOVED_READER,
        NEW_OWNER,
        NEW_WRITER,
        FORBIDDEN_BYPASS,
        DUPLICATE_OWNER,
        UNKNOWN_SEMANTIC_DRIFT
    }

    private static final Pattern LINE_SUFFIX = Pattern.compile(
            "\\s+lines?\\s+\\d+(?:-\\d+)?(?:,\\s*\\d+(?:-\\d+)?)*");

    private S01InventoryDriftClassifier() {
    }

    static Kind classify(JsonNode beforeCorrective, JsonNode afterCorrective) {
        Set<String> beforeOwners = ownerClasses(beforeCorrective.get("mutableOwners"));
        Set<String> afterOwners = ownerClasses(afterCorrective.get("mutableOwners"));
        if (afterOwners.size() > beforeOwners.size()
                && !beforeOwners.containsAll(afterOwners)) {
            return Kind.NEW_OWNER;
        }
        if (hasDuplicateLiveOwner(afterCorrective.get("mutableOwners"))) {
            return Kind.DUPLICATE_OWNER;
        }

        List<Site> beforeSites = parseSites(beforeCorrective.get("accessSites"));
        List<Site> afterSites = parseSites(afterCorrective.get("accessSites"));

        Map<String, Site> beforeByPhysical = indexByPhysical(beforeSites);
        Map<String, Site> afterByPhysical = indexByPhysical(afterSites);

        Set<String> addedPhysical = new LinkedHashSet<>(afterByPhysical.keySet());
        addedPhysical.removeAll(beforeByPhysical.keySet());
        Set<String> removedPhysical = new LinkedHashSet<>(beforeByPhysical.keySet());
        removedPhysical.removeAll(afterByPhysical.keySet());

        for (String key : addedPhysical) {
            Site site = afterByPhysical.get(key);
            if (isForbiddenBypass(site)) {
                return Kind.FORBIDDEN_BYPASS;
            }
        }

        Map<SemanticKey, Integer> beforeCounts = semanticCounts(beforeSites);
        Map<SemanticKey, Integer> afterCounts = semanticCounts(afterSites);

        for (Map.Entry<SemanticKey, Integer> entry : afterCounts.entrySet()) {
            SemanticKey key = entry.getKey();
            int before = beforeCounts.getOrDefault(key, 0);
            int after = entry.getValue();
            if (after > before && isWriterRole(key.role())) {
                return Kind.NEW_WRITER;
            }
        }

        for (Map.Entry<SemanticKey, Integer> entry : beforeCounts.entrySet()) {
            SemanticKey key = entry.getKey();
            int before = entry.getValue();
            int after = afterCounts.getOrDefault(key, 0);
            if (after < before) {
                if (isReaderRole(key.role())) {
                    return Kind.REMOVED_READER;
                }
                return Kind.UNKNOWN_SEMANTIC_DRIFT;
            }
        }

        for (Map.Entry<SemanticKey, Integer> entry : afterCounts.entrySet()) {
            SemanticKey key = entry.getKey();
            int before = beforeCounts.getOrDefault(key, 0);
            int after = entry.getValue();
            if (after > before && isReaderRole(key.role())) {
                return Kind.NEW_ALLOWED_READER;
            }
        }

        Set<SemanticKey> beforeKeys = beforeCounts.keySet();
        Set<SemanticKey> afterKeys = afterCounts.keySet();
        if (beforeKeys.equals(afterKeys)
                && beforeCounts.equals(afterCounts)
                && (!addedPhysical.isEmpty() || !removedPhysical.isEmpty())) {
            return Kind.RENAME_OR_MOVE_ONLY;
        }

        if (!addedPhysical.isEmpty() || !removedPhysical.isEmpty() || !beforeKeys.equals(afterKeys)) {
            return Kind.UNKNOWN_SEMANTIC_DRIFT;
        }
        return Kind.RENAME_OR_MOVE_ONLY;
    }

    private static boolean hasDuplicateLiveOwner(JsonNode owners) {
        Set<String> live = new TreeSet<>();
        for (JsonNode owner : owners) {
            if (owner.path("isCurrentMutableProductionOwner").asBoolean(false)
                    && owner.path("migratesToAcceptedState").asBoolean(false)) {
                String name = owner.path("class").asText();
                if (!live.add(name)) {
                    return true;
                }
            }
        }
        Map<String, String> leafOwner = new LinkedHashMap<>();
        for (JsonNode owner : owners) {
            if (!owner.path("isCurrentMutableProductionOwner").asBoolean(false)) {
                continue;
            }
            String name = owner.path("class").asText();
            for (JsonNode leaf : owner.path("categoryALeaves")) {
                String previous = leafOwner.put(leaf.asText(), name);
                if (previous != null && !previous.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> ownerClasses(JsonNode owners) {
        Set<String> names = new TreeSet<>();
        owners.forEach(owner -> names.add(owner.path("class").asText()));
        return names;
    }

    private static List<Site> parseSites(JsonNode sites) {
        List<Site> parsed = new ArrayList<>();
        sites.forEach(site -> parsed.add(Site.from(site)));
        return parsed;
    }

    private static Map<String, Site> indexByPhysical(List<Site> sites) {
        Map<String, Site> indexed = new LinkedHashMap<>();
        for (Site site : sites) {
            indexed.put(site.physicalKey(), site);
        }
        return indexed;
    }

    private static Map<SemanticKey, Integer> semanticCounts(List<Site> sites) {
        Map<SemanticKey, Integer> counts = new TreeMap<>();
        for (Site site : sites) {
            SemanticKey key = new SemanticKey(site.owner, site.role);
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static boolean isWriterRole(String role) {
        return role.startsWith("WRITER");
    }

    private static boolean isReaderRole(String role) {
        return role.startsWith("READER");
    }

    private static boolean isForbiddenBypass(Site site) {
        return site.module.contains("spring-boot-autoconfigure")
                && isWriterRole(site.role)
                && !(site.role.equals("WRITER_DELIVERY_TRIGGER")
                || site.role.equals("WRITER_INDIRECT"));
    }

    private static String stripLineNumbers(String method) {
        return LINE_SUFFIX.matcher(method).replaceAll("").trim();
    }

    private record Site(String owner, String className, String method, String role, String module) {
        static Site from(JsonNode node) {
            return new Site(
                    node.path("owner").asText(),
                    node.path("class").asText(),
                    stripLineNumbers(node.path("method").asText()),
                    node.path("role").asText(),
                    node.path("module").asText());
        }

        String physicalKey() {
            return module + "|" + className + "|" + method;
        }
    }

    private record SemanticKey(String owner, String role) implements Comparable<SemanticKey> {
        SemanticKey {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(role);
        }

        @Override
        public int compareTo(SemanticKey other) {
            int byOwner = owner.compareTo(other.owner);
            return byOwner != 0 ? byOwner : role.compareTo(other.role);
        }
    }
}
