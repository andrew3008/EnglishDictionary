package space.br1440.platform.errorhandling.core.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Slf4j
@RequiredArgsConstructor
public final class ReactiveSecurityProblemSupportVerifier implements SmartInitializingSingleton {

    private final ObjectProvider<ReactiveSecurityProblemSupport> problemSupportProvider;
    private final ObjectProvider<SecurityWebFilterChain> securityChainsProvider;

    @Override
    public void afterSingletonsInstantiated() {
        ReactiveSecurityProblemSupport support = problemSupportProvider.getIfAvailable();
        if (support == null) {
            return;
        }

        long chainsCount = securityChainsProvider.stream().count();
        if (chainsCount == 0L) {
            log.debug("ReactiveSecurityProblemSupport bean is present, "
                    + "but no SecurityWebFilterChain beans were found in the context — skipping integration verification.");
        }
    }
}
