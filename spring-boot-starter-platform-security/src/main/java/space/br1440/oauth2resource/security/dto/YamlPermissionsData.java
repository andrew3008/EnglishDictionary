package space.br1440.oauth2resource.security.dto;

import java.util.List;
import java.util.Map;

public class YamlPermissionsData {

    private Map<String, List<String>> role_permission_binding;

    public YamlPermissionsData(Map<String, List<String>> role_permission_binding) {
        this.role_permission_binding = role_permission_binding;
    }

    public YamlPermissionsData() {
    }


    public Map<String, List<String>> getRole_permission_binding() {
        return role_permission_binding;
    }

    public void setRole_permission_binding(Map<String, List<String>> role_permission_binding) {
        this.role_permission_binding = role_permission_binding;
    }
}
