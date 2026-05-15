package space.br1440.platform.errorhandling.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ReactiveSecurityProblemSupportVerifier")
class ReactiveSecurityProblemSupportVerifierTest {

    @Test
    @DisplayName("Без ReactiveSecurityProblemSupport — verifier выходит молча")
    void noProblemSupportNoOp() {
        // given
        ObjectProvider<ReactiveSecurityProblemSupport> supportProvider = mockProvider(null);
        ObjectProvider<SecurityWebFilterChain> chainsProvider = mockChainProvider(Collections.emptyList());

        ReactiveSecurityProblemSupportVerifier verifier =
                new ReactiveSecurityProblemSupportVerifier(supportProvider, chainsProvider);

        // when
        Executable run = verifier::afterSingletonsInstantiated;

        // then
        assertDoesNotThrow(run);
    }

    @Test
    @DisplayName("ProblemSupport есть, но цепочек нет — verifier выходит без INFO")
    void supportButNoChainsExitsSilently() {
        // given
        ReactiveSecurityProblemSupport support = mock(ReactiveSecurityProblemSupport.class);
        ObjectProvider<ReactiveSecurityProblemSupport> supportProvider = mockProvider(support);
        ObjectProvider<SecurityWebFilterChain> chainsProvider = mockChainProvider(Collections.emptyList());

        ReactiveSecurityProblemSupportVerifier verifier =
                new ReactiveSecurityProblemSupportVerifier(supportProvider, chainsProvider);

        // when
        Executable run = verifier::afterSingletonsInstantiated;

        // then
        assertDoesNotThrow(run);
    }

    @Test
    @DisplayName("ProblemSupport + цепочки есть — verifier завершается без исключений")
    void supportAndChainsCompletesSuccessfully() {
        // given
        ReactiveSecurityProblemSupport support = mock(ReactiveSecurityProblemSupport.class);
        ObjectProvider<ReactiveSecurityProblemSupport> supportProvider = mockProvider(support);
        SecurityWebFilterChain chain1 = mock(SecurityWebFilterChain.class);
        SecurityWebFilterChain chain2 = mock(SecurityWebFilterChain.class);
        ObjectProvider<SecurityWebFilterChain> chainsProvider =
                mockChainProvider(Arrays.asList(chain1, chain2));

        ReactiveSecurityProblemSupportVerifier verifier =
                new ReactiveSecurityProblemSupportVerifier(supportProvider, chainsProvider);

        // when
        Executable run = verifier::afterSingletonsInstantiated;

        // then
        assertDoesNotThrow(run);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SecurityWebFilterChain> mockChainProvider(List<SecurityWebFilterChain> chains) {
        ObjectProvider<SecurityWebFilterChain> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenReturn(chains.stream());
        return provider;
    }
}
