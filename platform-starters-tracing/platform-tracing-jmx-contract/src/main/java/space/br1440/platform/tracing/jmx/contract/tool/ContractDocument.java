package space.br1440.platform.tracing.jmx.contract.tool;

import java.util.List;
import java.util.Map;

record ContractDocument(
        Map<String, Object> root,
        String schemaId,
        int schemaMajor,
        int schemaMinor,
        int observationSchemaVersion,
        String schemaDigest,
        String descriptorDigest,
        String policyDigestAlgorithm,
        int policyDigestVersion
) {

    @SuppressWarnings("unchecked")
    Map<String, Object> section(String name) {
        return (Map<String, Object>) root.get(name);
    }

    @SuppressWarnings("unchecked")
    List<Object> list(String section, String name) {
        return (List<Object>) section(section).get(name);
    }
}
