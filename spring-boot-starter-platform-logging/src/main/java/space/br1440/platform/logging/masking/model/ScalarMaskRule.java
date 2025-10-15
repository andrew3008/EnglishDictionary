package space.br1440.platform.logging.masking.model;

import java.util.Set;

public class ScalarMaskRule extends MaskRule {

    private final Set<String> applyToKeys;
    private final String mask;

    public ScalarMaskRule(String name, Set<String> applyToKeys, String mask) {
        this.name = name;
        this.applyToKeys = applyToKeys;
        this.mask = mask;
    }

    public Set<String> applyToKeys() {
        return applyToKeys;
    }

    public String mask() {
        return mask;
    }

}
