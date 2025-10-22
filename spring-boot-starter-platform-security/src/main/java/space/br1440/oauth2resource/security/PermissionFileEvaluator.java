package space.br1440.oauth2resource.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import space.br1440.oauth2resource.security.dto.YamlPermissionsData;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PermissionFileEvaluator {

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final Map<String, List<String>> cache = new LinkedHashMap<>();

    @Value("${security.policy.file-location}")
    private String policyFileLocation;


    @PostConstruct
    public void init() throws IOException {
        Resource resource = new ClassPathResource(policyFileLocation);
        File policyFile = resource.getFile();
        try(InputStream inputStream = new FileInputStream(policyFile)) {
            final YamlPermissionsData permissionsData = yamlMapper.readValue(inputStream, YamlPermissionsData.class);
            cache.putAll(permissionsData.getRole_permission_binding());
        }
    }

    private Set<String> getRolesList(Authentication authentication) {
        return authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .map(s -> StringUtils.replace(s, "ROLE_", StringUtils.EMPTY))
                .collect(Collectors.toSet());
    }

    public boolean hasPermission(Authentication authentication, String permission) {
        if (!(authentication instanceof JwtAuthenticationToken)) {
            return false;
        }
        Set<String> roles = getRolesList(authentication);
        for (String role: roles) {
            if (cache.containsKey(role) && cache.get(role).contains(permission)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyPermission(Authentication authentication, String... permissions) {
        for (String permission: permissions) {
            if (hasPermission(authentication, permission)) {
                return true;
            }
        }
        return false;
    }




}
