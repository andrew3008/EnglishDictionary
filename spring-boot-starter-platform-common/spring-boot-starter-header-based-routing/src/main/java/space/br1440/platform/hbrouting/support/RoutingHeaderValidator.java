package space.br1440.platform.hbrouting.support;

import space.br1440.platform.hbrouting.config.RoutingProperties;

public class RoutingHeaderValidator {

    private final RoutingProperties properties;

    public RoutingHeaderValidator(RoutingProperties properties) {
        this.properties = properties;
    }

    public boolean isValid(String value) {
        if (value == null) {
            return false;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        if (trimmed.length() > properties.getMaxHeaderLength()) {
            return false;
        }

        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);

            boolean allowed =
                    Character.isLetterOrDigit(ch)
                            || ch == ':'
                            || ch == '/'
                            || ch == '-'
                            || ch == '_'
                            || ch == '.'
                            || ch == ','
                            || ch == ' ';

            if (!allowed) {
                return false;
            }
        }

        return true;
    }
}
