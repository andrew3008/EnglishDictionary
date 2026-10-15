package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentPomContractTest {

    private static final String FIXTURE_VERSION = "0.0.0";

    @Test
    void pom_contract_accepts_project_name_preceded_by_organization_name(@TempDir Path directory) throws IOException {
        // given
        Path pomFile = PomContract.writeFixture(directory, "organization-first.pom", """
                  <organization><name>Other Organization</name></organization>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>%s</name>
                  <description>%s</description>
                """.formatted(
                PomContract.GROUP_ID,
                PomContract.ARTIFACT_ID,
                FIXTURE_VERSION,
                PomContract.NAME,
                PomContract.DESCRIPTION));

        // expect
        PomContract.assertMatches(pomFile, FIXTURE_VERSION);
    }

    @Test
    void pom_contract_rejects_organization_name_in_place_of_project_name(@TempDir Path directory) throws IOException {
        // given
        Path pomFile = PomContract.writeFixture(directory, "missing-project-name.pom", """
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <description>%s</description>
                  <organization><name>%s</name></organization>
                """.formatted(
                PomContract.GROUP_ID,
                PomContract.ARTIFACT_ID,
                FIXTURE_VERSION,
                PomContract.DESCRIPTION,
                PomContract.NAME));

        // expect
        assertThatThrownBy(() -> PomContract.assertMatches(pomFile, FIXTURE_VERSION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project/name");
    }

    @Test
    void pom_contract_rejects_duplicate_project_name(@TempDir Path directory) throws IOException {
        // given
        Path pomFile = PomContract.writeFixture(directory, "duplicate-project-name.pom", """
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>%s</name>
                  <name>Second Declared Name</name>
                  <description>%s</description>
                """.formatted(
                PomContract.GROUP_ID,
                PomContract.ARTIFACT_ID,
                FIXTURE_VERSION,
                PomContract.NAME,
                PomContract.DESCRIPTION));

        // expect
        assertThatThrownBy(() -> PomContract.assertMatches(pomFile, FIXTURE_VERSION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate POM element: project/name");
    }

    @Test
    void pom_contract_rejects_dependencies_declared_inside_profile(@TempDir Path directory) throws IOException {
        // given
        Path pomFile = PomContract.writeFixture(directory, "profile-dependencies.pom", """
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>%s</name>
                  <description>%s</description>
                  <profiles>
                    <profile>
                      <id>extra</id>
                      <dependencies>
                        <dependency>
                          <groupId>org.example</groupId>
                          <artifactId>example</artifactId>
                          <version>1.0.0</version>
                        </dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                """.formatted(
                PomContract.GROUP_ID,
                PomContract.ARTIFACT_ID,
                FIXTURE_VERSION,
                PomContract.NAME,
                PomContract.DESCRIPTION));

        // expect
        assertThatThrownBy(() -> PomContract.assertMatches(pomFile, FIXTURE_VERSION))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("dependencies");
    }
}
