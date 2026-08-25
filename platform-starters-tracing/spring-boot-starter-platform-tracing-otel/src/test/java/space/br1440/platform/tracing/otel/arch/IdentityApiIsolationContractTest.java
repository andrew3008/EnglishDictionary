package space.br1440.platform.tracing.otel.arch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentityApiIsolationContractTest {

    @Test
    void infrastructureIdentityTypesDoNotEnterPlatformApi() {
        // given
        var infrastructureTypeNames = new String[]{
                "RequestContextAccessor",
                "RequestIdentityAccessor",
                "RequestIdentityContext",
                "RequestIdentityBinder",
                "RequestIdentityScope"
        };

        // when
        for (String simpleName : infrastructureTypeNames) {
            // then
            assertThatThrownBy(() -> Class.forName("space.br1440.platform.tracing.api." + simpleName))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }
}
