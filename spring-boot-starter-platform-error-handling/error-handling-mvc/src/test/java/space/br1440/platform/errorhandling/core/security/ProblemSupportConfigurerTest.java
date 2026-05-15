package space.br1440.platform.errorhandling.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.web.servlet.HandlerExceptionResolver;
import space.br1440.platform.errorhandling.testing.MockSecurityTest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockSecurityTest
@DisplayName("ProblemSupportConfigurer")
class ProblemSupportConfigurerTest {

    @Test
    @DisplayName("init() вызывает http.exceptionHandling() с SecurityProblemSupport")
    void init_registersSecurityProblemSupport() throws Exception {
        // given
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        SecurityProblemSupport problemSupport = new SecurityProblemSupport(resolver);
        ProblemSupportConfigurer configurer = new ProblemSupportConfigurer(problemSupport);
        HttpSecurity http = mock(HttpSecurity.class);
        when(http.exceptionHandling(any())).thenReturn(http);

        // when
        configurer.init(http);

        // then
        verify(http).exceptionHandling(any());
    }
}
