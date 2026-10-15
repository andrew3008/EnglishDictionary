package space.br1440.platform.tracing.jmx.contract.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class HelmRouteLimitsParityTest {

    private static final String POLICY_TEMPLATE_PROPERTY = "platformTracing.helmPolicyTemplate";
    private static final String WIRE_CONTRACT_RESOURCE = "/jmx-wire-contract.yaml";

    private static final String ROUTE_LIMITS_BEGIN = "{{- define \"platform-tracing.v1._routeLimits\" -}}";
    private static final String ROUTE_LIMITS_END = "{{- end -}}";
    private static final String LEVELS_LIST_BEGIN = "{{- $levels := list ";
    private static final String LEVELS_LIST_END = "-}}";
    private static final String VALIDATION_LEVEL_USAGE = "(has $validation.level $levels)";
    private static final String LOG_LEVEL_USAGE = "(has $diagnostics.logLevel $levels)";
    private static final Pattern LEVEL_TOKEN = Pattern.compile("[A-Z][A-Z0-9_]*");

    private static final String PARITY_DESCRIPTION = "route limits in %s must match %s";
    private static final String LEVELS_PARITY_DESCRIPTION = "%s levels in %s must match %s.values";
    private static final String BEGIN_MISSING_MESSAGE = "Helm template has no %s";
    private static final String END_MISSING_MESSAGE = "Helm template has no %s after %s";
    private static final String INTEGER_MISSING_MESSAGE = "Helm _routeLimits has no integer %s";
    private static final String LEVEL_USAGE_MISSING_MESSAGE = "Helm template has no level check %s";
    private static final String LEVEL_LIST_MISSING_MESSAGE = "Helm template has no $levels list before %s";
    private static final String LEVEL_LIST_END_MISSING_MESSAGE = "Helm $levels list is not closed before %s";
    private static final String LEVEL_LIST_EMPTY_MESSAGE = "Helm $levels list before %s has no uppercase tokens";
    private static final String PROPERTY_MISSING_MESSAGE = "System property %s is not set; configure it in the Gradle test task";
    private static final String RESOURCE_MISSING_MESSAGE = "Classpath resource %s is missing";

    @Test
    void helmRouteLimitsMatchWireContract() throws IOException, URISyntaxException {
        // given
        Map<String, Object> wireGraph = ContractDocumentFactory.load(wireContract()).section("wireGraph");
        RouteLimits contractLimits = new RouteLimits(
                ContractValues.integer(wireGraph.get("maxRouteEntries"), "wireGraph.maxRouteEntries"),
                ContractValues.integer(wireGraph.get("maxRoutePrefixUtf8Bytes"), "wireGraph.maxRoutePrefixUtf8Bytes")
        );
        Path policyTemplate = policyTemplate();

        // when
        RouteLimits helmLimits = helmRouteLimits(Files.readString(policyTemplate));

        // then
        assertThat(helmLimits)
                .as(PARITY_DESCRIPTION.formatted(policyTemplate, WIRE_CONTRACT_RESOURCE))
                .isEqualTo(contractLimits);
    }

    @Test
    void helmPolicyLevelsMatchWireContract() throws IOException, URISyntaxException {
        // given
        ContractDocument contract = ContractDocumentFactory.load(wireContract());
        List<String> contractValidationLevels = tokenValues(contract, "validationLevel");
        List<String> contractLogLevels = tokenValues(contract, "logLevel");
        Path policyTemplate = policyTemplate();
        String template = Files.readString(policyTemplate);

        // when
        List<String> helmValidationLevels = helmLevelValues(template, VALIDATION_LEVEL_USAGE);
        List<String> helmLogLevels = helmLevelValues(template, LOG_LEVEL_USAGE);

        // then
        assertThat(helmValidationLevels)
                .as(LEVELS_PARITY_DESCRIPTION.formatted(
                        "validation", policyTemplate, "validationLevel"))
                .containsExactlyElementsOf(contractValidationLevels);
        assertThat(helmLogLevels)
                .as(LEVELS_PARITY_DESCRIPTION.formatted(
                        "diagnostics log", policyTemplate, "logLevel"))
                .containsExactlyElementsOf(contractLogLevels);
    }

    private static RouteLimits helmRouteLimits(String template) {
        String body = routeLimitsBody(template);
        return new RouteLimits(
                intValue(body, "maxRouteEntries"),
                intValue(body, "maxRoutePrefixUtf8Bytes")
        );
    }

    private static String routeLimitsBody(String template) {
        int begin = template.indexOf(ROUTE_LIMITS_BEGIN);
        if (begin < 0) {
            throw new IllegalStateException(BEGIN_MISSING_MESSAGE.formatted(ROUTE_LIMITS_BEGIN));
        }

        int bodyStart = begin + ROUTE_LIMITS_BEGIN.length();
        int end = template.indexOf(ROUTE_LIMITS_END, bodyStart);
        if (end < 0) {
            throw new IllegalStateException(END_MISSING_MESSAGE.formatted(ROUTE_LIMITS_END, ROUTE_LIMITS_BEGIN));
        }

        return template.substring(bodyStart, end);
    }

    private static int intValue(String body, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":\\s*(\\d+)\\s*$").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(INTEGER_MISSING_MESSAGE.formatted(key));
        }

        return Integer.parseInt(matcher.group(1));
    }

    private static List<String> tokenValues(ContractDocument contract, String sectionName) {
        Map<String, Object> section = contract.section(sectionName);
        return ContractValues.strings(section.get("values"), sectionName + ".values");
    }

    private static List<String> helmLevelValues(String template, String usage) {
        int usageIndex = template.indexOf(usage);
        if (usageIndex < 0) {
            throw new IllegalStateException(LEVEL_USAGE_MISSING_MESSAGE.formatted(usage));
        }

        int listBegin = template.lastIndexOf(LEVELS_LIST_BEGIN, usageIndex);
        if (listBegin < 0) {
            throw new IllegalStateException(LEVEL_LIST_MISSING_MESSAGE.formatted(usage));
        }

        int valuesBegin = listBegin + LEVELS_LIST_BEGIN.length();
        int listEnd = template.indexOf(LEVELS_LIST_END, valuesBegin);
        if (listEnd < 0 || listEnd > usageIndex) {
            throw new IllegalStateException(LEVEL_LIST_END_MISSING_MESSAGE.formatted(usage));
        }

        List<String> values = LEVEL_TOKEN.matcher(template.substring(valuesBegin, listEnd))
                .results()
                .map(MatchResult::group)
                .toList();
        if (values.isEmpty()) {
            throw new IllegalStateException(LEVEL_LIST_EMPTY_MESSAGE.formatted(usage));
        }

        return values;
    }

    private static Path policyTemplate() {
        String configured = System.getProperty(POLICY_TEMPLATE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(PROPERTY_MISSING_MESSAGE.formatted(POLICY_TEMPLATE_PROPERTY));
        }

        return Path.of(configured);
    }

    private static Path wireContract() throws URISyntaxException {
        URL resource = HelmRouteLimitsParityTest.class.getResource(WIRE_CONTRACT_RESOURCE);
        if (resource == null) {
            throw new IllegalStateException(RESOURCE_MISSING_MESSAGE.formatted(WIRE_CONTRACT_RESOURCE));
        }

        return Path.of(resource.toURI());
    }

    private record RouteLimits(int maxRouteEntries, int maxRoutePrefixUtf8Bytes) {
    }
}
