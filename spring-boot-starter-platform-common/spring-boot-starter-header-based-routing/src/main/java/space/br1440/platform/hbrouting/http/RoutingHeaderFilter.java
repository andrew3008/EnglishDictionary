package space.br1440.platform.hbrouting.http;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import space.br1440.platform.hbrouting.config.RoutingProperties;
import space.br1440.platform.hbrouting.context.MeshHeaders;
import space.br1440.platform.hbrouting.context.RoutingContextHolder;
import space.br1440.platform.hbrouting.support.RoutingHeaderValidator;

public class RoutingHeaderFilter extends OncePerRequestFilter {

    private final RoutingProperties properties;
    private final RoutingHeaderValidator validator;

    public RoutingHeaderFilter(RoutingProperties properties, RoutingHeaderValidator validator) {
        this.properties = properties;
        this.validator = validator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            String headerValue = request.getHeader(MeshHeaders.FEATURE_BRANCH);

            if (headerValue != null && !headerValue.isBlank()) {
                if (!properties.isValidateHeader() || validator.isValid(headerValue)) {
                    RoutingContextHolder.set(headerValue.trim());
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            RoutingContextHolder.clear();
        }
    }
}