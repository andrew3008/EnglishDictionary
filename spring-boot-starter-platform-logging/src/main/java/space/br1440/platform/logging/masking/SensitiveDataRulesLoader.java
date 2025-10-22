package space.br1440.platform.logging.masking;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.micrometer.common.util.StringUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.CollectionUtils;
import space.br1440.platform.logging.configuration.LoggingProperties;
import space.br1440.platform.logging.exception.PlatformLoggingOperationException;
import space.br1440.platform.logging.exception.PlatformLoggingValidationException;
import space.br1440.platform.logging.masking.model.ArrayMaskRule;
import space.br1440.platform.logging.masking.model.ScalarMaskRule;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@UtilityClass
public class SensitiveDataRulesLoader {

    private static final String CLASSPATH_PREFIX = "classpath:";
    public static final String PLATFORM_SENSITIVE_DATA_RULES_FILE_PATH = "classpath:space/br1440/platform/logging/platform-log-masking-rules.yaml";

    public static MaskRules loadRules(LoggingProperties loggingProperties) {
        MaskRules maskRules = SensitiveDataRulesLoader.loadRulesFromYaml(PLATFORM_SENSITIVE_DATA_RULES_FILE_PATH);

        String customRulesFile = loggingProperties.getCustomSensitiveDataRulesFilePath();
        if (!StringUtils.isEmpty(customRulesFile)) {
            MaskRules customRules = SensitiveDataRulesLoader.loadRulesFromYaml(customRulesFile);
            maskRules.getScalarRules().addAll(customRules.getScalarRules());
            maskRules.getArrayRules().addAll(customRules.getArrayRules());
        }

        return maskRules;
    }

    private static MaskRules loadRulesFromYaml(String filePath) {
        if (filePath.startsWith(CLASSPATH_PREFIX)) {
            filePath = filePath.substring(CLASSPATH_PREFIX.length());
        }
        return loadRulesFromYaml(new ClassPathResource(filePath));
    }

    private static MaskRules loadRulesFromYaml(ClassPathResource rulesFile) {
        try (InputStream is = rulesFile.getInputStream()) {
            YAMLMapper yamlMapper = new YAMLMapper();
            MaskRulesYaml maskRulesYaml = yamlMapper.readValue(is, MaskRulesYaml.class);

            List<ScalarMaskRule> scalarRules = new ArrayList<>();
            List<ArrayMaskRule> arrayRules = new ArrayList<>();

            for (MaskRuleYaml rule : maskRulesYaml.getRules()) {
                validateRule(rule);

                switch (rule.getType().toLowerCase()) {
                    case "scalar" -> scalarRules.add(new ScalarMaskRule(
                            rule.getName(),
                            new HashSet<>(rule.getApplyToKeys()),
                            rule.getMask()
                    ));

                    case "array" -> arrayRules.add(new ArrayMaskRule(
                            rule.getName(),
                            new HashSet<>(rule.getApplyToKeys()),
                            rule.getMaskItem()
                    ));

                    default -> throw new PlatformLoggingValidationException(
                            "Unsupported rule type: " + rule.getType() + " for rule: " + rule.getName()
                    );
                }
            }

            return new MaskRules(scalarRules, arrayRules);
        } catch (Exception e) {
            String message = String.format("Failed to load masking rules from YAML file: %s", rulesFile);
            throw new PlatformLoggingOperationException(message, e);
        }
    }

    private static void validateRule(MaskRuleYaml rule) {
        if (StringUtils.isEmpty(rule.getName())) {
            throw new PlatformLoggingValidationException("Rule name must be provided");
        }

        if (StringUtils.isEmpty(rule.getType())) {
            throw new PlatformLoggingValidationException("Rule type must be provided for rule: " + rule.getName());
        }

        if (CollectionUtils.isEmpty(rule.getApplyToKeys())) {
            throw new PlatformLoggingValidationException("applyToKeys must be provided for rule: " + rule.getName());
        }

        if ("scalar".equalsIgnoreCase(rule.getType()) && StringUtils.isEmpty(rule.getMask())) {
            throw new PlatformLoggingValidationException("Mask must be provided for scalar rule: " + rule.getName());
        }

        if ("array".equalsIgnoreCase(rule.getType()) && StringUtils.isEmpty(rule.getMaskItem())) {
            throw new PlatformLoggingValidationException("MaskItem must be provided for array rule: " + rule.getName());
        }
    }

    @Getter
    @Setter
    public static class MaskRulesYaml {
        private List<MaskRuleYaml> rules;
    }

    @Getter
    @Setter
    public static class MaskRuleYaml {
        private String name;
        private String type;

        @JsonProperty("apply-to-keys")
        private List<String> applyToKeys;

        private String mask;

        @JsonProperty("mask-item")
        private String maskItem;
    }

}
