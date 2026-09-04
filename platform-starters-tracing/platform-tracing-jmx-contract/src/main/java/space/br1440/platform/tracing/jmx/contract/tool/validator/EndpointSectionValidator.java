package space.br1440.platform.tracing.jmx.contract.tool.validator;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.jmx.contract.tool.ContractValidationException;
import space.br1440.platform.tracing.jmx.contract.tool.ContractValues;

import lombok.experimental.UtilityClass;

@UtilityClass
final class EndpointSectionValidator {

    static void validate(ContractValidationContext context) {
        Map<String, Object> endpoints = context.section("endpoints");
        Set<String> objectNameIds = context.objectNames().keySet();
        Map<String, Object> openTypes = context.openTypes();
        ContractValues.assertKeys(endpoints, "endpoints", objectNameIds, objectNameIds);

        for (Map.Entry<String, Object> entry : endpoints.entrySet()) {
            String path = "endpoints." + entry.getKey();
            Map<String, Object> endpoint = ContractValues.map(entry.getValue(), path);
            Set<String> keys = Set.of("objectName", "role", "attributes", "operations");
            ContractValues.assertKeys(endpoint, path, keys, keys);

            String objectName = ContractValues.string(endpoint.get("objectName"), path + ".objectName");
            if (!entry.getKey().equals(objectName) || !objectNameIds.contains(objectName)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_ENDPOINT_OBJECT_NAME_INVALID",
                        path + " must reference its canonical ObjectName"
                );
            }

            String role = ContractValues.string(endpoint.get("role"), path + ".role");
            if (!Set.of("mutation", "observation", "readiness-anchor").contains(role)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_ENDPOINT_ROLE_INVALID",
                        "Unsupported endpoint role: " + role
                );
            }

            validateAttributes(ContractValues.list(endpoint.get("attributes"), path + ".attributes"), path, openTypes);
            validateOperations(ContractValues.list(endpoint.get("operations"), path + ".operations"), path, openTypes);
        }

        Map<String, Object> mutation = ContractValues.map(endpoints.get("settingsMutation"), "endpoints.settingsMutation");
        if (!ContractValues.list(mutation.get("attributes"), "endpoints.settingsMutation.attributes").isEmpty()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_MUTATION_SURFACE_INVALID",
                    "Mutation endpoint must not expose attributes"
            );
        }

        List<Object> operations = ContractValues.list(mutation.get("operations"), "endpoints.settingsMutation.operations");
        if (operations.size() != 1
                || !"applySnapshot".equals(ContractValues.map(operations.getFirst(), "mutation.operation").get("name"))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_MUTATION_SURFACE_INVALID",
                    "Mutation endpoint must expose only applySnapshot"
            );
        }
    }

    private static void validateAttributes(List<Object> attributes,
                                           String endpointPath,
                                           Map<String, Object> openTypes) {
        Set<String> names = new HashSet<>();
        for (int index = 0; index < attributes.size(); index++) {
            String path = endpointPath + ".attributes[" + index + ']';
            Map<String, Object> attribute = ContractValues.map(attributes.get(index), path);
            Set<String> keys = Set.of("name", "javaType", "openType", "readable", "writable", "required", "nullable");
            ContractValues.assertKeys(attribute, path, keys, keys);

            if (!names.add(ContractValues.string(attribute.get("name"), path + ".name"))) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_DUPLICATE_MEMBER",
                        "Duplicate endpoint attribute at " + endpointPath
                );
            }

            ContractValues.string(attribute.get("javaType"), path + ".javaType");
            OpenTypeSectionValidator.requireOpenType(openTypes, attribute.get("openType"), path + ".openType");
            ContractValues.bool(attribute.get("readable"), path + ".readable");
            ContractValues.bool(attribute.get("writable"), path + ".writable");
            ContractValues.bool(attribute.get("required"), path + ".required");
            ContractValues.bool(attribute.get("nullable"), path + ".nullable");
        }
    }

    private static void validateOperations(List<Object> operations,
                                           String endpointPath,
                                           Map<String, Object> openTypes) {
        Set<String> names = new HashSet<>();
        for (int index = 0; index < operations.size(); index++) {
            String path = endpointPath + ".operations[" + index + ']';
            Map<String, Object> operation = ContractValues.map(operations.get(index), path);
            Set<String> keys = Set.of("name", "impact", "returnJavaType", "returnOpenType", "parameters");
            ContractValues.assertKeys(operation, path, keys, keys);

            if (!names.add(ContractValues.string(operation.get("name"), path + ".name"))) {
                throw new ContractValidationException("JMX_SCHEMA_DUPLICATE_MEMBER", "Duplicate endpoint operation at " + endpointPath);
            }

            String impact = ContractValues.string(operation.get("impact"), path + ".impact");
            if (!Set.of("ACTION", "ACTION_INFO", "INFO", "UNKNOWN").contains(impact)) {
                throw new ContractValidationException("JMX_SCHEMA_OPERATION_IMPACT_INVALID", "Unsupported MBean operation impact: " + impact);
            }

            ContractValues.string(operation.get("returnJavaType"), path + ".returnJavaType");
            OpenTypeSectionValidator.requireOpenType(openTypes, operation.get("returnOpenType"), path + ".returnOpenType");

            List<Object> parameters = ContractValues.list(operation.get("parameters"), path + ".parameters");
            Set<String> parameterNames = new HashSet<>();
            for (int parameterIndex = 0; parameterIndex < parameters.size(); parameterIndex++) {
                String parameterPath = path + ".parameters[" + parameterIndex + ']';
                Map<String, Object> parameter = ContractValues.map(parameters.get(parameterIndex), parameterPath);
                Set<String> parameterKeys = Set.of("name", "javaType", "openType", "required", "nullable");
                ContractValues.assertKeys(parameter, parameterPath, parameterKeys, parameterKeys);

                if (!parameterNames.add(ContractValues.string(parameter.get("name"), parameterPath + ".name"))) {
                    throw new ContractValidationException("JMX_SCHEMA_DUPLICATE_MEMBER", "Duplicate operation parameter at " + path);
                }

                ContractValues.string(parameter.get("javaType"), parameterPath + ".javaType");
                OpenTypeSectionValidator.requireOpenType(openTypes, parameter.get("openType"), parameterPath + ".openType");

                ContractValues.bool(parameter.get("required"), parameterPath + ".required");
                ContractValues.bool(parameter.get("nullable"), parameterPath + ".nullable");
            }
        }
    }
}
