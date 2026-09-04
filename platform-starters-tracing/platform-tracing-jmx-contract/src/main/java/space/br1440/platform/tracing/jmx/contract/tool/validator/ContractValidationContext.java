package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.Map;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

record ContractValidationContext(Map<String, Object> root,
                                 Map<String, Object> schema,
                                 Map<String, Object> objectNames,
                                 Map<String, Object> openTypes) {
    Map<String, Object> section(String name) {
        return ContractValues.map(root.get(name), name);
    }
}
