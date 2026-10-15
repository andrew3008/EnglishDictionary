package space.br1440.platform.tracing.jmx.contract.tool;

import java.util.Map;

record ContractDocument(Map<String, Object> root,
                        String schemaId,
                        String schemaDigest,
                        String descriptorDigest) {

    @SuppressWarnings("unchecked")
    Map<String, Object> section(String name) {
        return (Map<String, Object>) root.get(name);
    }
}
