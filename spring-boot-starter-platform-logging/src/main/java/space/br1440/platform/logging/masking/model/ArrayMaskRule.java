package space.br1440.platform.logging.masking.model;

import java.util.Set;

public class ArrayMaskRule extends MaskRule {

    private final Set<String> applyToKeys;
    private final String maskItem;

    public ArrayMaskRule(String name, Set<String> applyToKeys, String maskItem) {
        this.name = name;
        this.applyToKeys = applyToKeys;
        this.maskItem = maskItem;
    }

    public Set<String> applyToKeys() {
        return applyToKeys;
    }

    public String maskItem() {
        return maskItem;
    }
}
