package space.br1440.oauth2resource.autoconfiguration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import space.br1440.oauth2resource.security.CustomBr1440MethodSecurityExpressionHandler;
import space.br1440.oauth2resource.security.PermissionFileEvaluator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityAutoConfiguration {

    @Value("#{'${security.origins.allowed}'.split(',')}")
    private List<String> allowedOriginsPatterns;

    @Bean
    @Profile("!local")
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain defaultFilterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**", "/service/**").permitAll().requestMatchers("/api/**").authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(br1440JwtAuthenticationConverter())))
                .build();
    }

    @Bean
    @Profile("local")
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain localFilterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration corsConfig = new CorsConfiguration().applyPermitDefaultValues();
        corsConfig.setAllowedOriginPatterns(allowedOriginsPatterns);
        corsConfig.addAllowedMethod("*");
        corsConfig.addAllowedHeader("*");
        corsConfig.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);
        return source;
    }

    @Bean
    public Converter<Jwt, ? extends AbstractAuthenticationToken> br1440JwtAuthenticationConverter() {
        Function<Object, Map<String, Object>> getAsMap = o -> o == null ? new HashMap<>() : (Map<String, Object>)o;
        Function<Object, List<String>> getAsList = o -> o == null ? new ArrayList<>() : (List<String>)o;
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter((jwt) -> {
            var realmAccess = getAsMap.apply(jwt.getClaims().get("realm_access"));
            var realmAccessRoles = getAsList.apply(realmAccess.get("roles"));
            return realmAccessRoles
                    .stream()
                    .map(roleName -> "ROLE_" + roleName) // prefix required by Spring Security for roles.
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        });
        return jwtConverter;
    }

    @Bean
    public PermissionFileEvaluator permissionFileEvaluator() {
        return new PermissionFileEvaluator();
    }

    @Bean
    @Primary
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        return new CustomBr1440MethodSecurityExpressionHandler(permissionFileEvaluator());
    }


}
