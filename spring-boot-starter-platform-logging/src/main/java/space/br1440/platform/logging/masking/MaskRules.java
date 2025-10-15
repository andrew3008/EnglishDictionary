package space.br1440.platform.logging.masking;

import lombok.Getter;
import lombok.Setter;
import space.br1440.platform.logging.masking.model.ArrayMaskRule;
import space.br1440.platform.logging.masking.model.ScalarMaskRule;

import java.util.List;

@Getter
@Setter
public class MaskRules {

    private List<ScalarMaskRule> scalarRules;
    private List<ArrayMaskRule> arrayRules;

    public MaskRules(List<ScalarMaskRule> scalarRules, List<ArrayMaskRule> arrayRules) {
        this.scalarRules = scalarRules;
        this.arrayRules = arrayRules;
    }

}
