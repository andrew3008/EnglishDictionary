package space.br1440.platform.tracing.jmx.contract.tool;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class ContractSupport {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "schema",
            "planes",
            "versions",
            "objectNames",
            "endpoints",
            "openTypes",
            "capabilities",
            "readinessEvidence",
            "wireGraph",
            "policyDigest"
    );
    private static final Set<String> SIMPLE_OPEN_TYPES = Set.of(
            "boolean", "int", "long", "double", "string", "stringArray", "objectName"
    );
    private static final List<String> REQUIRED_POLICY_FIELDS = List.of(
            "samplingEnabled",
            "samplingRatio",
            "samplingRouteRatios",
            "validationEnabled",
            "validationStrict",
            "exporterEnabled",
            "traceControlHeadersEnabled",
            "diagnosticsLogLevel"
    );
    private static final List<String> REQUIRED_POLICY_TYPES = List.of(
            "boolean", "double", "sortedUtf8DoubleMap", "boolean", "boolean", "boolean", "boolean", "asciiToken"
    );

    private ContractSupport() {
    }

    static ContractDocument loadContract(Path path) throws IOException {
        return validateAndCreate(loadYaml(path, "JMX contract"));
    }

    static GoldenDocument loadGolden(Path path) throws IOException {
        Map<String, Object> root = loadYaml(path, "JMX golden vectors");
        assertKeys(
                root,
                "golden",
                Set.of("contract", "policies", "invalidPolicies", "invalidContracts"),
                Set.of("contract", "policies", "invalidPolicies", "invalidContracts")
        );
        return new GoldenDocument(root);
    }

    static void verifyGolden(ContractDocument contract, GoldenDocument goldenDocument) {
        Map<String, Object> golden = goldenDocument.root();
        verifyGoldenIdentity(contract, map(golden.get("contract"), "golden.contract"));
        verifyGoldenPolicies(contract, list(golden.get("policies"), "golden.policies"));
        verifyInvalidPolicies(contract, list(golden.get("invalidPolicies"), "golden.invalidPolicies"));
        verifyInvalidContracts(contract, list(golden.get("invalidContracts"), "golden.invalidContracts"));
    }

    private static String canonical(Object value) {
        StringBuilder result = new StringBuilder();
        appendCanonical(result, value);
        return result.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalidType(path, "map");
        }
        for (Object key : raw.keySet()) {
            if (!(key instanceof String)) {
                throw invalidType(path + ".<key>", "string");
            }
        }
        return (Map<String, Object>) raw;
    }

    static List<Object> list(Object value, String path) {
        if (!(value instanceof List<?> raw)) {
            throw invalidType(path, "list");
        }
        return new ArrayList<>(raw);
    }

    static String string(Object value, String path) {
        if (!(value instanceof String result) || result.isBlank()) {
            throw invalidType(path, "non-blank string");
        }
        return result;
    }

    static int integer(Object value, String path) {
        if (!(value instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue()) {
            throw invalidType(path, "integer");
        }
        return number.intValue();
    }

    static boolean bool(Object value, String path) {
        if (!(value instanceof Boolean result)) {
            throw invalidType(path, "boolean");
        }
        return result;
    }

    static List<String> strings(Object value, String path) {
        List<Object> values = list(value, path);
        List<String> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            result.add(string(values.get(index), path + '[' + index + ']'));
        }
        return result;
    }

    private static ContractDocument validateAndCreate(Map<String, Object> root) {
        assertKeys(root, "contract", TOP_LEVEL_KEYS, TOP_LEVEL_KEYS);
        validateSchema(map(root.get("schema"), "schema"));
        validatePlanes(map(root.get("planes"), "planes"));
        Map<String, Object> versions = map(root.get("versions"), "versions");
        validateVersions(versions);
        Map<String, Object> objectNames = map(root.get("objectNames"), "objectNames");
        validateObjectNames(objectNames);
        Map<String, Object> openTypes = map(root.get("openTypes"), "openTypes");
        validateOpenTypes(openTypes);
        validateEndpoints(
                map(root.get("endpoints"), "endpoints"),
                objectNames.keySet(),
                openTypes
        );
        validateCapabilities(map(root.get("capabilities"), "capabilities"));
        validateReadinessEvidence(
                map(root.get("readinessEvidence"), "readinessEvidence"),
                openTypes
        );
        validateWireGraph(map(root.get("wireGraph"), "wireGraph"));
        validatePolicyDigest(map(root.get("policyDigest"), "policyDigest"));

        Map<String, Object> schema = map(root.get("schema"), "schema");
        Map<String, Object> descriptorSurface = new LinkedHashMap<>();
        descriptorSurface.put("versions", root.get("versions"));
        descriptorSurface.put("objectNames", root.get("objectNames"));
        descriptorSurface.put("endpoints", root.get("endpoints"));
        descriptorSurface.put("openTypes", root.get("openTypes"));

        Map<String, Object> digest = map(root.get("policyDigest"), "policyDigest");
        return new ContractDocument(
                root,
                string(schema.get("id"), "schema.id"),
                integer(schema.get("major"), "schema.major"),
                integer(schema.get("minor"), "schema.minor"),
                integer(versions.get("observationSchema"), "versions.observationSchema"),
                sha256(canonical(root)),
                sha256(canonical(descriptorSurface)),
                string(digest.get("algorithmId"), "policyDigest.algorithmId"),
                integer(digest.get("algorithmVersion"), "policyDigest.algorithmVersion")
        );
    }

    private static void validateSchema(Map<String, Object> schema) {
        assertKeys(
                schema,
                "schema",
                Set.of("id", "major", "minor", "canonicalization", "compatibility"),
                Set.of("id", "major", "minor", "canonicalization", "compatibility")
        );
        string(schema.get("id"), "schema.id");
        int major = integer(schema.get("major"), "schema.major");
        int minor = integer(schema.get("minor"), "schema.minor");
        if (major != 1 || minor < 0) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_VERSION_INCOMPATIBLE", "Generator supports JMX schema major version 1 only");
        }

        Map<String, Object> canonicalization = map(schema.get("canonicalization"), "schema.canonicalization");
        assertKeys(
                canonicalization,
                "schema.canonicalization",
                Set.of("format", "unicodeNormalization", "mapKeyOrdering", "sequenceOrdering", "lineEnding"),
                Set.of("format", "unicodeNormalization", "mapKeyOrdering", "sequenceOrdering", "lineEnding")
        );
        requireValue(canonicalization, "format", "canonical-tree-v1", "schema.canonicalization");
        requireValue(canonicalization, "unicodeNormalization", "NFC", "schema.canonicalization");
        requireValue(canonicalization, "mapKeyOrdering", "unicode-code-point", "schema.canonicalization");
        requireValue(canonicalization, "sequenceOrdering", "declaration-order", "schema.canonicalization");
        requireValue(canonicalization, "lineEnding", "LF", "schema.canonicalization");

        Map<String, Object> compatibility = map(schema.get("compatibility"), "schema.compatibility");
        assertKeys(
                compatibility,
                "schema.compatibility",
                Set.of(
                        "minimumConsumerMajor",
                        "maximumConsumerMajor",
                        "unknownFieldPolicy",
                        "incompatibleVersionPolicy",
                        "mismatchLifecycle"
                ),
                Set.of(
                        "minimumConsumerMajor",
                        "maximumConsumerMajor",
                        "unknownFieldPolicy",
                        "incompatibleVersionPolicy",
                        "mismatchLifecycle"
                )
        );
        if (integer(compatibility.get("minimumConsumerMajor"), "schema.compatibility.minimumConsumerMajor") != 1
                || integer(compatibility.get("maximumConsumerMajor"), "schema.compatibility.maximumConsumerMajor") != 1) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_VERSION_INCOMPATIBLE", "Consumer compatibility must be bounded to schema major 1");
        }
        requireValue(compatibility, "unknownFieldPolicy", "reject", "schema.compatibility");
        requireValue(compatibility, "incompatibleVersionPolicy", "fail-closed", "schema.compatibility");
        requireValue(compatibility, "mismatchLifecycle", "sticky-failed", "schema.compatibility");
    }

    private static void validateVersions(Map<String, Object> versions) {
        assertKeys(
                versions,
                "versions",
                Set.of("observationSchema"),
                Set.of("observationSchema")
        );
        if (integer(versions.get("observationSchema"), "versions.observationSchema") <= 0) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_VERSION_INVALID",
                    "versions.observationSchema must be positive"
            );
        }
    }

    private static void validatePlanes(Map<String, Object> planes) {
        assertKeys(planes, "planes", Set.of("extension", "application"), Set.of("extension", "application"));
        Set<String> packages = new HashSet<>();
        for (String plane : List.of("extension", "application")) {
            Map<String, Object> configuration = map(planes.get(plane), "planes." + plane);
            assertKeys(configuration, "planes." + plane, Set.of("packageName"), Set.of("packageName"));
            String packageName = string(configuration.get("packageName"), "planes." + plane + ".packageName");
            if (!packageName.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_INVALID_PACKAGE", "Invalid generated package: " + packageName);
            }
            if (!packages.add(packageName)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_PLANE_IDENTITY_COLLISION", "Generated plane packages must differ");
            }
        }
    }

    private static void validateObjectNames(Map<String, Object> objectNames) {
        Set<String> required = Set.of(
                "settingsMutation",
                "settingsStatus",
                "extensionReadiness",
                "samplingObservation",
                "scrubbingObservation",
                "validationObservation",
                "exportObservation",
                "processorMetricsObservation",
                "diagnosticsObservation"
        );
        assertKeys(objectNames, "objectNames", required, required);
        Set<ObjectName> unique = new HashSet<>();
        for (Map.Entry<String, Object> entry : objectNames.entrySet()) {
            try {
                ObjectName objectName = ObjectName.getInstance(string(entry.getValue(), "objectNames." + entry.getKey()));
                if (!unique.add(objectName)) {
                    throw new ContractValidationException(
                            "JMX_SCHEMA_OBJECT_NAME_COLLISION", "ObjectName values must be unique");
                }
            } catch (MalformedObjectNameException exception) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_INVALID_OBJECT_NAME", "Invalid ObjectName at objectNames." + entry.getKey());
            }
        }
    }

    private static void validateEndpoints(
            Map<String, Object> endpoints,
            Set<String> objectNameIds,
            Map<String, Object> openTypes
    ) {
        assertKeys(endpoints, "endpoints", objectNameIds, objectNameIds);
        for (Map.Entry<String, Object> entry : endpoints.entrySet()) {
            String path = "endpoints." + entry.getKey();
            Map<String, Object> endpoint = map(entry.getValue(), path);
            assertKeys(
                    endpoint,
                    path,
                    Set.of("objectName", "role", "attributes", "operations"),
                    Set.of("objectName", "role", "attributes", "operations")
            );
            String objectName = string(endpoint.get("objectName"), path + ".objectName");
            if (!entry.getKey().equals(objectName) || !objectNameIds.contains(objectName)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_ENDPOINT_OBJECT_NAME_INVALID", path + " must reference its canonical ObjectName");
            }
            String role = string(endpoint.get("role"), path + ".role");
            if (!Set.of("mutation", "observation", "readiness-anchor").contains(role)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_ENDPOINT_ROLE_INVALID", "Unsupported endpoint role: " + role);
            }
            validateAttributes(list(endpoint.get("attributes"), path + ".attributes"), path, openTypes);
            validateOperations(list(endpoint.get("operations"), path + ".operations"), path, openTypes);
        }

        Map<String, Object> mutation = map(endpoints.get("settingsMutation"), "endpoints.settingsMutation");
        if (!list(mutation.get("attributes"), "endpoints.settingsMutation.attributes").isEmpty()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_MUTATION_SURFACE_INVALID", "Mutation endpoint must not expose attributes");
        }
        List<Object> operations = list(mutation.get("operations"), "endpoints.settingsMutation.operations");
        if (operations.size() != 1
                || !"applySnapshot".equals(map(operations.get(0), "mutation.operation").get("name"))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_MUTATION_SURFACE_INVALID", "Mutation endpoint must expose only applySnapshot");
        }
    }

    private static void validateAttributes(
            List<Object> attributes,
            String endpointPath,
            Map<String, Object> openTypes
    ) {
        Set<String> names = new HashSet<>();
        for (int index = 0; index < attributes.size(); index++) {
            String path = endpointPath + ".attributes[" + index + ']';
            Map<String, Object> attribute = map(attributes.get(index), path);
            assertKeys(
                    attribute,
                    path,
                    Set.of("name", "javaType", "openType", "readable", "writable", "required", "nullable"),
                    Set.of("name", "javaType", "openType", "readable", "writable", "required", "nullable")
            );
            if (!names.add(string(attribute.get("name"), path + ".name"))) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_DUPLICATE_MEMBER", "Duplicate endpoint attribute at " + endpointPath);
            }
            string(attribute.get("javaType"), path + ".javaType");
            requireOpenType(openTypes, attribute.get("openType"), path + ".openType");
            bool(attribute.get("readable"), path + ".readable");
            bool(attribute.get("writable"), path + ".writable");
            bool(attribute.get("required"), path + ".required");
            bool(attribute.get("nullable"), path + ".nullable");
        }
    }

    private static void validateOperations(
            List<Object> operations,
            String endpointPath,
            Map<String, Object> openTypes
    ) {
        Set<String> names = new HashSet<>();
        for (int index = 0; index < operations.size(); index++) {
            String path = endpointPath + ".operations[" + index + ']';
            Map<String, Object> operation = map(operations.get(index), path);
            assertKeys(
                    operation,
                    path,
                    Set.of("name", "impact", "returnJavaType", "returnOpenType", "parameters"),
                    Set.of("name", "impact", "returnJavaType", "returnOpenType", "parameters")
            );
            if (!names.add(string(operation.get("name"), path + ".name"))) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_DUPLICATE_MEMBER", "Duplicate endpoint operation at " + endpointPath);
            }
            String impact = string(operation.get("impact"), path + ".impact");
            if (!Set.of("ACTION", "ACTION_INFO", "INFO", "UNKNOWN").contains(impact)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_OPERATION_IMPACT_INVALID", "Unsupported MBean operation impact: " + impact);
            }
            string(operation.get("returnJavaType"), path + ".returnJavaType");
            requireOpenType(openTypes, operation.get("returnOpenType"), path + ".returnOpenType");
            List<Object> parameters = list(operation.get("parameters"), path + ".parameters");
            Set<String> parameterNames = new HashSet<>();
            for (int parameterIndex = 0; parameterIndex < parameters.size(); parameterIndex++) {
                String parameterPath = path + ".parameters[" + parameterIndex + ']';
                Map<String, Object> parameter = map(parameters.get(parameterIndex), parameterPath);
                assertKeys(
                        parameter,
                        parameterPath,
                        Set.of("name", "javaType", "openType", "required", "nullable"),
                        Set.of("name", "javaType", "openType", "required", "nullable")
                );
                if (!parameterNames.add(string(parameter.get("name"), parameterPath + ".name"))) {
                    throw new ContractValidationException(
                            "JMX_SCHEMA_DUPLICATE_MEMBER", "Duplicate operation parameter at " + path);
                }
                string(parameter.get("javaType"), parameterPath + ".javaType");
                requireOpenType(openTypes, parameter.get("openType"), parameterPath + ".openType");
                bool(parameter.get("required"), parameterPath + ".required");
                bool(parameter.get("nullable"), parameterPath + ".nullable");
            }
        }
    }

    private static void validateOpenTypes(Map<String, Object> openTypes) {
        if (openTypes.isEmpty()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_REQUIRED_FIELD_MISSING", "openTypes must not be empty");
        }
        Set<String> typeNames = new HashSet<>();
        for (Map.Entry<String, Object> entry : openTypes.entrySet()) {
            String path = "openTypes." + entry.getKey();
            Map<String, Object> descriptor = map(entry.getValue(), path);
            String kind = string(descriptor.get("kind"), path + ".kind");
            switch (kind) {
                case "composite" -> validateCompositeType(descriptor, path, openTypes, typeNames);
                case "tabular" -> validateTabularType(descriptor, path, openTypes, typeNames);
                case "map" -> validateMapType(descriptor, path, typeNames);
                default -> throw new ContractValidationException(
                        "JMX_SCHEMA_OPEN_TYPE_KIND_INVALID", "Unsupported Open Type kind: " + kind);
            }
        }
        assertAcyclicOpenTypes(openTypes);
    }

    private static void validateCompositeType(
            Map<String, Object> descriptor,
            String path,
            Map<String, Object> openTypes,
            Set<String> typeNames
    ) {
        assertKeys(
                descriptor,
                path,
                Set.of("kind", "typeName", "fields"),
                Set.of("kind", "typeName", "fields")
        );
        addTypeName(descriptor, path, typeNames);
        List<Object> fields = list(descriptor.get("fields"), path + ".fields");
        if (fields.isEmpty()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_REQUIRED_FIELD_MISSING", path + ".fields must not be empty");
        }
        Set<String> names = new HashSet<>();
        for (int index = 0; index < fields.size(); index++) {
            String fieldPath = path + ".fields[" + index + ']';
            Map<String, Object> field = map(fields.get(index), fieldPath);
            assertKeys(
                    field,
                    fieldPath,
                    Set.of("name", "type", "required", "nullable"),
                    Set.of("name", "type", "required", "nullable")
            );
            if (!names.add(string(field.get("name"), fieldPath + ".name"))) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_DUPLICATE_MEMBER", "Duplicate Open Type field at " + path);
            }
            validateTypeReference(string(field.get("type"), fieldPath + ".type"), openTypes, fieldPath);
            bool(field.get("required"), fieldPath + ".required");
            bool(field.get("nullable"), fieldPath + ".nullable");
        }
    }

    private static void validateTabularType(
            Map<String, Object> descriptor,
            String path,
            Map<String, Object> openTypes,
            Set<String> typeNames
    ) {
        assertKeys(
                descriptor,
                path,
                Set.of("kind", "typeName", "rowType", "indexFields"),
                Set.of("kind", "typeName", "rowType", "indexFields")
        );
        addTypeName(descriptor, path, typeNames);
        String rowType = string(descriptor.get("rowType"), path + ".rowType");
        Map<String, Object> row = map(openTypes.get(rowType), "openTypes." + rowType);
        if (!"composite".equals(row.get("kind"))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID", path + ".rowType must reference a composite type");
        }
        Set<String> rowFields = fieldNames(row, "openTypes." + rowType);
        List<String> indexFields = strings(descriptor.get("indexFields"), path + ".indexFields");
        if (indexFields.isEmpty() || !rowFields.containsAll(indexFields)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_TABULAR_INDEX_INVALID", path + ".indexFields must reference row fields");
        }
    }

    private static void validateMapType(
            Map<String, Object> descriptor,
            String path,
            Set<String> typeNames
    ) {
        assertKeys(
                descriptor,
                path,
                Set.of(
                        "kind", "typeName", "rowTypeName", "keyField", "keyType",
                        "valueField", "valueType", "indexFields"
                ),
                Set.of(
                        "kind", "typeName", "rowTypeName", "keyField", "keyType",
                        "valueField", "valueType", "indexFields"
                )
        );
        addTypeName(descriptor, path, typeNames);
        if (!typeNames.add(string(descriptor.get("rowTypeName"), path + ".rowTypeName"))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_NAME_COLLISION", "Open Type names must be unique");
        }
        String keyField = string(descriptor.get("keyField"), path + ".keyField");
        string(descriptor.get("valueField"), path + ".valueField");
        if (!SIMPLE_OPEN_TYPES.contains(string(descriptor.get("keyType"), path + ".keyType"))
                || !SIMPLE_OPEN_TYPES.contains(string(descriptor.get("valueType"), path + ".valueType"))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID", path + " map types must be simple Open Types");
        }
        List<String> indexes = strings(descriptor.get("indexFields"), path + ".indexFields");
        if (!indexes.equals(List.of(keyField))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_TABULAR_INDEX_INVALID", path + " must index the key field exactly");
        }
    }

    private static void assertAcyclicOpenTypes(Map<String, Object> openTypes) {
        Set<String> complete = new HashSet<>();
        Deque<String> visiting = new ArrayDeque<>();
        for (String type : openTypes.keySet()) {
            visitType(type, openTypes, complete, visiting);
        }
    }

    private static void visitType(
            String type,
            Map<String, Object> openTypes,
            Set<String> complete,
            Deque<String> visiting
    ) {
        if (complete.contains(type)) {
            return;
        }
        if (visiting.contains(type)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_CYCLE", "Open Type dependency cycle: " + visiting + " -> " + type);
        }
        visiting.addLast(type);
        Map<String, Object> descriptor = map(openTypes.get(type), "openTypes." + type);
        String kind = string(descriptor.get("kind"), "openTypes." + type + ".kind");
        if ("composite".equals(kind)) {
            for (Object rawField : list(descriptor.get("fields"), "openTypes." + type + ".fields")) {
                String reference = referencedType(string(
                        map(rawField, "openTypes." + type + ".field").get("type"),
                        "openTypes." + type + ".field.type"
                ));
                if (reference != null) {
                    visitType(reference, openTypes, complete, visiting);
                }
            }
        } else if ("tabular".equals(kind)) {
            visitType(string(descriptor.get("rowType"), "openTypes." + type + ".rowType"),
                    openTypes, complete, visiting);
        }
        visiting.removeLast();
        complete.add(type);
    }

    private static void validateCapabilities(Map<String, Object> capabilities) {
        assertKeys(capabilities, "capabilities", Set.of("required"), Set.of("required"));
        List<String> required = strings(capabilities.get("required"), "capabilities.required");
        if (required.isEmpty() || new HashSet<>(required).size() != required.size()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_CAPABILITIES_INVALID", "Required capabilities must be non-empty and unique");
        }
        for (String capability : required) {
            if (!capability.matches("[A-Z][A-Z0-9_]*")) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_CAPABILITIES_INVALID", "Invalid capability identifier: " + capability);
            }
        }
    }

    /**
     * Проверяет, что перечисленные измеренные факты готовности присутствуют в readinessSnapshot.
     * Штампованные константы в секции не допускаются: поле, сравниваемое с константой сборки,
     * ничего не проверяет и в контракт не входит.
     */
    private static void validateReadinessEvidence(
            Map<String, Object> readinessEvidence,
            Map<String, Object> openTypes
    ) {
        assertKeys(
                readinessEvidence,
                "readinessEvidence",
                Set.of("distributionFields", "topologyFields"),
                Set.of("distributionFields", "topologyFields")
        );
        Set<String> evidenceFields = new LinkedHashSet<>();
        evidenceFields.addAll(strings(
                readinessEvidence.get("distributionFields"), "readinessEvidence.distributionFields"));
        List<String> topologyFields = strings(
                readinessEvidence.get("topologyFields"),
                "readinessEvidence.topologyFields");
        evidenceFields.addAll(topologyFields);
        if (evidenceFields.size() < 2) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_READINESS_EVIDENCE_INVALID",
                    "Readiness evidence field lists must be unique and non-empty");
        }
        if (topologyFields.size() < 2) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_READINESS_EVIDENCE_INVALID",
                    "Topology evidence must describe both the extension and the SDK defining loader");
        }
        Map<String, Object> readiness = map(openTypes.get("readinessSnapshot"), "openTypes.readinessSnapshot");
        Set<String> readinessFields = fieldNames(readiness, "openTypes.readinessSnapshot");
        if (!readinessFields.containsAll(evidenceFields)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_READINESS_EVIDENCE_INVALID",
                    "Readiness snapshot must contain every readiness evidence field");
        }
    }

    private static void validateWireGraph(Map<String, Object> wireGraph) {
        Set<String> keys = Set.of(
                "maxDepth",
                "maxElements",
                "maxLogicalBytes",
                "maxStringUtf8Bytes",
                "maxRouteEntries",
                "maxRoutePrefixUtf8Bytes",
                "maxCapabilityEntries",
                "allowedNeutralTypes",
                "allowedArrayComponents"
        );
        assertKeys(wireGraph, "wireGraph", keys, keys);
        for (String key : keys) {
            if (key.startsWith("max") && integer(wireGraph.get(key), "wireGraph." + key) <= 0) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_WIRE_BOUND_INVALID", "wireGraph." + key + " must be positive");
            }
        }
        List<String> neutral = strings(wireGraph.get("allowedNeutralTypes"), "wireGraph.allowedNeutralTypes");
        List<String> arrays = strings(wireGraph.get("allowedArrayComponents"), "wireGraph.allowedArrayComponents");
        if (neutral.isEmpty() || arrays.isEmpty()
                || new HashSet<>(neutral).size() != neutral.size()
                || new HashSet<>(arrays).size() != arrays.size()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_WIRE_ALLOWLIST_INVALID", "Wire graph allowlists must be non-empty and unique");
        }
    }

    private static void validatePolicyDigest(Map<String, Object> digest) {
        Set<String> keys = Set.of(
                "algorithmId",
                "algorithmVersion",
                "encodingVersion",
                "domainSeparator",
                "routeOrdering",
                "normalizeNegativeZero",
                "validLogLevels",
                "fields"
        );
        assertKeys(digest, "policyDigest", keys, keys);
        requireValue(digest, "algorithmId", "SHA-256", "policyDigest");
        if (integer(digest.get("algorithmVersion"), "policyDigest.algorithmVersion") != 1
                || integer(digest.get("encodingVersion"), "policyDigest.encodingVersion") != 0) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_DIGEST_VERSION_INVALID", "Unsupported policy digest version");
        }
        string(digest.get("domainSeparator"), "policyDigest.domainSeparator");
        requireValue(digest, "routeOrdering", "unsigned-utf8-lexicographic", "policyDigest");
        bool(digest.get("normalizeNegativeZero"), "policyDigest.normalizeNegativeZero");
        List<String> logLevels = strings(digest.get("validLogLevels"), "policyDigest.validLogLevels");
        if (!new LinkedHashSet<>(logLevels).equals(
                new LinkedHashSet<>(List.of("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE")))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_DIGEST_LOG_LEVELS_INVALID", "Policy digest log levels are invalid");
        }

        List<Object> fields = list(digest.get("fields"), "policyDigest.fields");
        if (fields.size() != REQUIRED_POLICY_FIELDS.size()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_DIGEST_FIELD_INVALID", "Policy digest must define eight fields");
        }
        for (int index = 0; index < fields.size(); index++) {
            String path = "policyDigest.fields[" + index + ']';
            Map<String, Object> field = map(fields.get(index), path);
            assertKeys(field, path, Set.of("id", "name", "type"), Set.of("id", "name", "type"));
            if (integer(field.get("id"), path + ".id") != index + 1
                    || !REQUIRED_POLICY_FIELDS.get(index).equals(field.get("name"))
                    || !REQUIRED_POLICY_TYPES.get(index).equals(field.get("type"))) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_DIGEST_FIELD_INVALID", "Policy digest field changed at index " + index);
            }
        }
    }

    private static void verifyGoldenIdentity(ContractDocument contract, Map<String, Object> expected) {
        assertKeys(
                expected,
                "golden.contract",
                Set.of(
                        "schemaId",
                        "schemaMajor",
                        "schemaMinor",
                        "observationSchemaVersion",
                        "schemaDigestSha256",
                        "descriptorDigestSha256",
                        "policyDigestAlgorithmId",
                        "policyDigestAlgorithmVersion"
                ),
                Set.of(
                        "schemaId",
                        "schemaMajor",
                        "schemaMinor",
                        "observationSchemaVersion",
                        "schemaDigestSha256",
                        "descriptorDigestSha256",
                        "policyDigestAlgorithmId",
                        "policyDigestAlgorithmVersion"
                )
        );
        Map<String, Object> actual = Map.of(
                "schemaId", contract.schemaId(),
                "schemaMajor", contract.schemaMajor(),
                "schemaMinor", contract.schemaMinor(),
                "observationSchemaVersion", contract.observationSchemaVersion(),
                "schemaDigestSha256", contract.schemaDigest(),
                "descriptorDigestSha256", contract.descriptorDigest(),
                "policyDigestAlgorithmId", contract.policyDigestAlgorithm(),
                "policyDigestAlgorithmVersion", contract.policyDigestVersion()
        );
        if (!actual.equals(expected)) {
            throw new ContractValidationException(
                    "JMX_GOLDEN_IDENTITY_MISMATCH",
                    "JMX contract identity differs from golden. actual=" + actual
            );
        }
    }

    private static void verifyGoldenPolicies(ContractDocument contract, List<Object> vectors) {
        if (vectors.isEmpty()) {
            throw new ContractValidationException(
                    "JMX_GOLDEN_VECTOR_MISSING", "Policy golden vectors must not be empty");
        }
        Map<String, String> equivalenceDigests = new HashMap<>();
        for (int index = 0; index < vectors.size(); index++) {
            String path = "golden.policies[" + index + ']';
            Map<String, Object> vector = map(vectors.get(index), path);
            assertKeys(
                    vector,
                    path,
                    Set.of("id", "equivalenceGroup", "policy", "expectedSha256"),
                    Set.of("id", "policy", "expectedSha256")
            );
            string(vector.get("id"), path + ".id");
            String actual = PolicyDigestEncoder.digest(contract, map(vector.get("policy"), path + ".policy"));
            String expected = string(vector.get("expectedSha256"), path + ".expectedSha256");
            if (!actual.equals(expected)) {
                throw new ContractValidationException(
                        "JMX_GOLDEN_POLICY_DIGEST_MISMATCH",
                        "Policy digest differs from golden at " + path + ". actual=" + actual
                );
            }
            if (vector.containsKey("equivalenceGroup")) {
                String group = string(vector.get("equivalenceGroup"), path + ".equivalenceGroup");
                String prior = equivalenceDigests.putIfAbsent(group, actual);
                if (prior != null && !prior.equals(actual)) {
                    throw new ContractValidationException(
                            "JMX_GOLDEN_CANONICALIZATION_MISMATCH",
                            "Equivalent policy vectors have different digests: " + group
                    );
                }
            }
        }
    }

    private static void verifyInvalidPolicies(ContractDocument contract, List<Object> vectors) {
        for (int index = 0; index < vectors.size(); index++) {
            String path = "golden.invalidPolicies[" + index + ']';
            Map<String, Object> vector = map(vectors.get(index), path);
            assertKeys(
                    vector,
                    path,
                    Set.of("id", "policy", "expectedCode"),
                    Set.of("id", "policy", "expectedCode")
            );
            string(vector.get("id"), path + ".id");
            String expectedCode = string(vector.get("expectedCode"), path + ".expectedCode");
            try {
                PolicyDigestEncoder.digest(contract, map(vector.get("policy"), path + ".policy"));
                throw new ContractValidationException(
                        "JMX_GOLDEN_NEGATIVE_VECTOR_ACCEPTED", "Invalid policy vector was accepted: " + path);
            } catch (ContractValidationException exception) {
                if (!expectedCode.equals(exception.code())) {
                    throw new ContractValidationException(
                            "JMX_GOLDEN_NEGATIVE_VECTOR_MISMATCH",
                            "Invalid policy vector returned " + exception.code() + " instead of " + expectedCode
                    );
                }
            }
        }
    }

    private static void verifyInvalidContracts(ContractDocument contract, List<Object> vectors) {
        for (int index = 0; index < vectors.size(); index++) {
            String path = "golden.invalidContracts[" + index + ']';
            Map<String, Object> vector = map(vectors.get(index), path);
            assertKeys(
                    vector,
                    path,
                    Set.of("id", "mutation", "expectedCode"),
                    Set.of("id", "mutation", "expectedCode")
            );
            string(vector.get("id"), path + ".id");
            String mutation = string(vector.get("mutation"), path + ".mutation");
            String expectedCode = string(vector.get("expectedCode"), path + ".expectedCode");
            Map<String, Object> mutated = deepCopyMap(contract.root());
            mutateContract(mutated, mutation);
            try {
                validateAndCreate(mutated);
                throw new ContractValidationException(
                        "JMX_GOLDEN_NEGATIVE_VECTOR_ACCEPTED", "Invalid contract vector was accepted: " + path);
            } catch (ContractValidationException exception) {
                if (!expectedCode.equals(exception.code())) {
                    throw new ContractValidationException(
                            "JMX_GOLDEN_NEGATIVE_VECTOR_MISMATCH",
                            "Invalid contract vector returned " + exception.code() + " instead of " + expectedCode
                    );
                }
            }
        }
    }

    private static void mutateContract(Map<String, Object> root, String mutation) {
        switch (mutation) {
            case "addUnknownTopLevelField" -> root.put("unknownField", "must-fail");
            case "setIncompatibleSchemaMajor" -> map(root.get("schema"), "schema").put("major", 2);
            case "setInvalidObservationSchemaVersion" ->
                    map(root.get("versions"), "versions").put("observationSchema", 0);
            case "changeDigestFieldType" -> {
                Map<String, Object> digest = map(root.get("policyDigest"), "policyDigest");
                List<Object> fields = list(digest.get("fields"), "policyDigest.fields");
                map(fields.get(1), "policyDigest.fields[1]").put("type", "string");
            }
            case "removeRequiredObjectName" -> map(root.get("objectNames"), "objectNames")
                    .remove("settingsMutation");
            case "duplicateObjectName" -> {
                Map<String, Object> objectNames = map(root.get("objectNames"), "objectNames");
                objectNames.put("settingsStatus", objectNames.get("settingsMutation"));
            }
            default -> throw new ContractValidationException(
                    "JMX_GOLDEN_MUTATION_UNKNOWN", "Unsupported golden contract mutation: " + mutation);
        }
    }

    private static void requireOpenType(Map<String, Object> openTypes, Object value, String path) {
        String type = string(value, path);
        if (!openTypes.containsKey(type)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID", path + " references unknown Open Type " + type);
        }
    }

    private static void validateTypeReference(
            String type,
            Map<String, Object> openTypes,
            String path
    ) {
        if (SIMPLE_OPEN_TYPES.contains(type)) {
            return;
        }
        String reference = referencedType(type);
        if (reference == null || !openTypes.containsKey(reference)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID", path + " references unknown Open Type " + type);
        }
        String expectedKind = type.substring(0, type.indexOf(':'));
        String actualKind = string(
                map(openTypes.get(reference), "openTypes." + reference).get("kind"),
                "openTypes." + reference + ".kind"
        );
        if (!("composite".equals(expectedKind) && "composite".equals(actualKind))
                && !("tabular".equals(expectedKind)
                && ("tabular".equals(actualKind) || "map".equals(actualKind)))) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_REFERENCE_INVALID", path + " has incompatible Open Type kind");
        }
    }

    private static String referencedType(String type) {
        int separator = type.indexOf(':');
        if (separator < 0) {
            return null;
        }
        return type.substring(separator + 1);
    }

    private static Set<String> fieldNames(Map<String, Object> composite, String path) {
        Set<String> result = new LinkedHashSet<>();
        for (Object rawField : list(composite.get("fields"), path + ".fields")) {
            Map<String, Object> field = map(rawField, path + ".field");
            result.add(string(field.get("name"), path + ".field.name"));
        }
        return result;
    }

    private static void addTypeName(
            Map<String, Object> descriptor,
            String path,
            Set<String> typeNames
    ) {
        String typeName = string(descriptor.get("typeName"), path + ".typeName");
        if (!typeNames.add(typeName)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_OPEN_TYPE_NAME_COLLISION", "Open Type names must be unique: " + typeName);
        }
    }

    private static void requireValue(
            Map<String, Object> values,
            String key,
            String expected,
            String path
    ) {
        String actual = string(values.get(key), path + '.' + key);
        if (!expected.equals(actual)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_VALUE_UNSUPPORTED", path + '.' + key + " must be " + expected);
        }
    }

    private static void assertKeys(
            Map<String, Object> values,
            String path,
            Set<String> allowed,
            Set<String> required
    ) {
        Set<String> unknown = new LinkedHashSet<>(values.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_UNKNOWN_FIELD", path + " has unknown fields: " + unknown);
        }
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(values.keySet());
        if (!missing.isEmpty()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_REQUIRED_FIELD_MISSING", path + " is missing fields: " + missing);
        }
    }

    private static ContractValidationException invalidType(String path, String expected) {
        return new ContractValidationException(
                "JMX_SCHEMA_FIELD_TYPE_MISMATCH", path + " must be a " + expected);
    }

    private static Map<String, Object> loadYaml(Path path, String label) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(64);
        options.setCodePointLimit(1_000_000);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            if (loaded == null) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_EMPTY", label + " is empty: " + path);
            }
            return map(loaded, label);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put((String) entry.getKey(), deepCopy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(deepCopy(item));
            }
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> value) {
        return (Map<String, Object>) deepCopy(value);
    }

    private static void appendCanonical(StringBuilder output, Object rawValue) {
        Object value = rawValue instanceof String string
                ? Normalizer.normalize(string, Normalizer.Form.NFC)
                : rawValue;
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = map(rawMap, "canonical.map");
            List<String> keys = new ArrayList<>(map.keySet());
            keys.sort(ContractSupport::compareCodePoints);
            output.append("map{");
            for (String key : keys) {
                appendCanonical(output, key);
                appendCanonical(output, map.get(key));
            }
            output.append('}');
        } else if (value instanceof List<?> list) {
            output.append("list[");
            for (Object item : list) {
                appendCanonical(output, item);
            }
            output.append(']');
        } else if (value instanceof String string) {
            byte[] encoded = string.getBytes(StandardCharsets.UTF_8);
            output.append("string:").append(encoded.length).append(':').append(string).append(';');
        } else if (value instanceof Boolean bool) {
            output.append(bool ? "boolean:true;" : "boolean:false;");
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger) {
            output.append("integer:").append(value).append(';');
        } else if (value instanceof Number number) {
            double numberValue = number.doubleValue();
            if (!Double.isFinite(numberValue)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_NUMBER_INVALID", "Canonical schema numbers must be finite");
            }
            output.append("double:").append(Double.toHexString(numberValue)).append(';');
        } else if (value == null) {
            output.append("null;");
        } else {
            throw new ContractValidationException(
                    "JMX_SCHEMA_FIELD_TYPE_MISMATCH",
                    "Canonical schema contains unsupported value type: " + value.getClass().getName()
            );
        }
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            int comparison = Integer.compare(leftCodePoint, rightCodePoint);
            if (comparison != 0) {
                return comparison;
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }
}
