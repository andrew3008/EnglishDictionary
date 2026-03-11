package space.br1440.platform.healthchecks.processor;

import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.health.AdditionalHealthEndpointPath;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.Set;

public class DynamicHealthEndpointGroup implements HealthEndpointGroup {

    private final HealthEndpointGroup delegate;
    private final Set<String> includes;

    public DynamicHealthEndpointGroup(@Nullable HealthEndpointGroup delegate,
                                      Set<String> includes) {
        this.delegate = delegate;
        this.includes = Collections.unmodifiableSet(includes);
    }

    @Override
    public boolean isMember(String contributorName) {
        return includes.contains(contributorName);
    }

    @Override
    public boolean showComponents(SecurityContext securityContext) {
        return delegate != null && delegate.showComponents(securityContext);
    }

    @Override
    public boolean showDetails(SecurityContext securityContext) {
        return delegate != null && delegate.showDetails(securityContext);
    }

    @Override
    public StatusAggregator getStatusAggregator() {
        return delegate != null
                ? delegate.getStatusAggregator()
                : StatusAggregator.getDefault();
    }

    @Override
    public HttpCodeStatusMapper getHttpCodeStatusMapper() {
        return delegate != null
                ? delegate.getHttpCodeStatusMapper()
                : HttpCodeStatusMapper.DEFAULT;
    }

    @Override
    @Nullable
    public AdditionalHealthEndpointPath getAdditionalPath() {
        return delegate != null ? delegate.getAdditionalPath() : null;
    }
}
