package space.br1440.platform.datasource.support.junit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.it.PublicRegularScopeProbe;
import space.br1440.platform.datasource.it.chaos.PublicChaosScopeProbe;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PgClusterScope resolution")
class PgClusterScopeTest {

    @Test
    @DisplayName("chaos package defaults to ISOLATED")
    void chaosPackage_isIsolated() {
        assertThat(PgClusterExtension.defaultScopeFor(PublicChaosScopeProbe.class))
                .isEqualTo(PgClusterScope.Mode.ISOLATED);
    }

    @Test
    @DisplayName("regular IT package defaults to SHARED")
    void regularIt_isShared() {
        assertThat(PgClusterExtension.defaultScopeFor(PublicRegularScopeProbe.class))
                .isEqualTo(PgClusterScope.Mode.SHARED);
    }
}
