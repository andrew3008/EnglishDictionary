package space.br1440.platform.tracing.jmx.contract.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import lombok.experimental.UtilityClass;

@UtilityClass
final class JmxWireLiteralSourceGate {

    static void verify(ContractDocument contract, List<Path> sourceRoots) throws IOException {
        Set<String> forbiddenLiterals = criticalWireLiterals(contract);

        List<String> violations = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            if (!Files.isDirectory(sourceRoot)) {
                throw new IllegalStateException("JMX source-gate directory is absent: " + sourceRoot);
            }

            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                List<Path> filteredPaths = paths.filter(Files::isRegularFile)
                        .filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList();

                for (Path path : filteredPaths) {
                    inspect(path, forbiddenLiterals, violations);
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Manual JMX wire literals are forbidden outside generated sources: "
                            + String.join(", ", violations)
            );
        }
    }

    private static Set<String> criticalWireLiterals(ContractDocument contract) {
        Set<String> result = new LinkedHashSet<>();
        for (Object objectName : contract.section("objectNames").values()) {
            result.add((String) objectName);
        }

        for (Map.Entry<String, Object> endpointEntry : contract.section("endpoints").entrySet()) {
            String path = "endpoints." + endpointEntry.getKey();
            Map<String, Object> endpoint = ContractValues.map(endpointEntry.getValue(), path);
            for (Object rawAttribute : ContractValues.list(endpoint.get("attributes"), "endpoint.attributes")) {
                result.add((String) ContractValues.map(rawAttribute, "endpoint.attribute").get("name"));
            }

            for (Object rawOperation : ContractValues.list(endpoint.get("operations"), "endpoint.operations")) {
                Map<String, Object> operation = ContractValues.map(rawOperation, "endpoint.operation");
                result.add((String) operation.get("name"));
                for (Object rawParameter : ContractValues.list(operation.get("parameters"), "endpoint.operation.parameters")) {
                    result.add((String) ContractValues.map(rawParameter, "endpoint.operation.parameter").get("name"));
                }
            }
        }

        for (Map.Entry<String, Object> typeEntry : contract.section("openTypes").entrySet()) {
            Map<String, Object> descriptor = ContractValues.map(typeEntry.getValue(), "openTypes." + typeEntry.getKey());
            result.add((String) descriptor.get("typeName"));

            if (descriptor.containsKey("rowTypeName")) {
                result.add((String) descriptor.get("rowTypeName"));
            }

            if ("map".equals(descriptor.get("kind"))) {
                result.add((String) descriptor.get("keyField"));
                result.add((String) descriptor.get("valueField"));
            }
        }

        return Set.copyOf(result);
    }

    private static void inspect(Path path, Set<String> forbiddenLiterals, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        boolean insideBlockComment = false;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            LineScan scan = scanLine(lines.get(lineIndex), insideBlockComment);
            insideBlockComment = scan.insideBlockComment();
            for (String literal : scan.literals()) {
                if (forbiddenLiterals.contains(literal)) {
                    violations.add(path + ":" + (lineIndex + 1) + ":" + literal);
                }
            }
        }
    }

    private static LineScan scanLine(String line, boolean startsInsideBlockComment) {
        List<String> literals = new ArrayList<>();
        boolean insideBlockComment = startsInsideBlockComment;

        int index = 0;
        while (index < line.length()) {
            if (insideBlockComment) {
                int end = line.indexOf("*/", index);
                if (end < 0) {
                    return new LineScan(literals, true);
                }

                insideBlockComment = false;
                index = (end + 2);
                continue;
            }

            char character = line.charAt(index);
            if (character == '/' && index + 1 < line.length()) {
                char next = line.charAt(index + 1);
                if (next == '/') {
                    break;
                }

                if (next == '*') {
                    insideBlockComment = true;
                    index += 2;
                    continue;
                }
            }
            if (character == '\'') {
                index = skipCharacterLiteral(line, index);
                continue;
            }

            if (character == '"') {
                StringBuilder value = new StringBuilder();
                index = readStringLiteral(line, index, value);
                literals.add(value.toString());
                continue;
            }

            index++;
        }

        return new LineScan(literals, insideBlockComment);
    }

    private static int readStringLiteral(String line, int openingQuote, StringBuilder value) {
        int index = (openingQuote + 1);
        while (index < line.length()) {
            char character = line.charAt(index);
            if (character == '\\' && index + 1 < line.length()) {
                value.append(unescape(line.charAt(index + 1)));
                index += 2;
                continue;
            }

            if (character == '"') {
                return (index + 1);
            }

            value.append(character);
            index++;
        }

        return index;
    }

    private static int skipCharacterLiteral(String line, int openingQuote) {
        int index = (openingQuote + 1);
        while (index < line.length()) {
            char character = line.charAt(index);
            if (character == '\\') {
                index += 2;
                continue;
            }

            if (character == '\'') {
                return index + 1;
            }

            index++;
        }

        return index;
    }

    private static char unescape(char escaped) {
        return switch (escaped) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case '0' -> '\0';
            default -> escaped;
        };
    }

    private record LineScan(List<String> literals, boolean insideBlockComment) {
    }
}
