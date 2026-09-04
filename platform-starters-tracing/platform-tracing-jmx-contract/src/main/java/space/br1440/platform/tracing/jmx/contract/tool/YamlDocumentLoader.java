package space.br1440.platform.tracing.jmx.contract.tool;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import lombok.experimental.UtilityClass;

@UtilityClass
final class YamlDocumentLoader {

    @SuppressWarnings("SameParameterValue")
    static Map<String, Object> load(Path path, String label) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(64);
        options.setCodePointLimit(1_000_000);

        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            if (loaded == null) {
                throw new ContractValidationException("JMX_SCHEMA_EMPTY", label + " is empty: " + path);
            }

            return ContractValues.map(loaded, label);
        }
    }
}
