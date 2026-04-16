package space.br1440.platform.hbrouting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.hbrouting")
public class RoutingProperties {

    private boolean enabled = true;

    private boolean validateHeader = true;

    private int maxHeaderLength = 512;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isValidateHeader() {
        return validateHeader;
    }

    public void setValidateHeader(boolean validateHeader) {
        this.validateHeader = validateHeader;
    }

    public int getMaxHeaderLength() {
        return maxHeaderLength;
    }

    public void setMaxHeaderLength(int maxHeaderLength) {
        this.maxHeaderLength = maxHeaderLength;
    }
}
