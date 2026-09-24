package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class ConsumerProject {

    private static final String GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2/";
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2";
    private static final String EXTRA_DIRECTORY = "extra";
    private static final String EXTRA_FILE = "keep.txt";

    private final String name;
    private final Dsl dsl;
    private final List<String> leadingRepositories = new ArrayList<>();
    private boolean publicJibRepositories;
    private boolean springBootSubstitute;
    private boolean extraDirectory;

    private ConsumerProject(String name, Dsl dsl) {
        this.name = name;
        this.dsl = dsl;
    }

    static ConsumerProject groovy(String name) {
        return new ConsumerProject(name, Dsl.GROOVY);
    }

    static ConsumerProject kotlin(String name) {
        return new ConsumerProject(name, Dsl.KOTLIN);
    }

    ConsumerProject withLeadingRepository(Path repository) {
        leadingRepositories.add(repository.toAbsolutePath().normalize().toUri().toString());
        return this;
    }

    ConsumerProject withPublicJibRepositories() {
        publicJibRepositories = true;
        return this;
    }

    ConsumerProject withSpringBootSubstitute() {
        springBootSubstitute = true;
        return this;
    }

    ConsumerProject withExtraDirectory() {
        extraDirectory = true;
        return this;
    }

    Path create(Path parent, IsolatedMavenRepository repository, String buildScript) throws IOException {
        Path project = parent.resolve(name);
        Files.createDirectories(project);

        Files.writeString(project.resolve("settings" + dsl.extension), settings(repository), StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build" + dsl.extension), buildScript, StandardCharsets.UTF_8);

        if (springBootSubstitute) {
            writeSpringBootSubstitute(project);
        }

        if (extraDirectory) {
            Files.createDirectories(project.resolve(EXTRA_DIRECTORY));
            Files.writeString(project.resolve(EXTRA_DIRECTORY).resolve(EXTRA_FILE), "consumer-extra\n", StandardCharsets.UTF_8);
        }

        return project;
    }

    private String settings(IsolatedMavenRepository repository) {
        List<String> pluginRepositories = new ArrayList<>(leadingRepositories);
        pluginRepositories.add(repository.uri());

        List<String> projectRepositories = new ArrayList<>(leadingRepositories);
        projectRepositories.add(repository.uri());

        if (publicJibRepositories) {
            pluginRepositories.addAll(List.of(GRADLE_PLUGIN_PORTAL, MAVEN_CENTRAL));
            projectRepositories.addAll(List.of(MAVEN_CENTRAL, GRADLE_PLUGIN_PORTAL));
        }

        return """
                pluginManagement {
                    repositories {
                        %s
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        %s
                    }
                }
                rootProject.name = %s
                """.formatted(repositoryBlock(pluginRepositories), repositoryBlock(projectRepositories), dsl.quote(name));
    }

    private String repositoryBlock(List<String> uris) {
        return uris.stream()
                .map(uri -> "maven { url = uri(" + dsl.quote(uri) + ") }")
                .collect(Collectors.joining("\n        "));
    }

    private static void writeSpringBootSubstitute(Path project) throws IOException {
        Path buildSrc = project.resolve("buildSrc");
        Files.createDirectories(buildSrc.resolve("src/main/java"));
        Files.createDirectories(buildSrc.resolve("src/main/resources/META-INF/gradle-plugins"));

        Files.writeString(buildSrc.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(
                buildSrc.resolve("src/main/resources/META-INF/gradle-plugins/org.springframework.boot.properties"),
                "implementation-class=SpringBootTaskSubstitutePlugin\n",
                StandardCharsets.UTF_8);

        Files.writeString(buildSrc.resolve("src/main/java/SpringBootTaskSubstitutePlugin.java"), """
                import org.gradle.api.Plugin;
                import org.gradle.api.Project;
                import org.gradle.api.tasks.JavaExec;
                import org.gradle.api.tasks.bundling.Jar;

                public final class SpringBootTaskSubstitutePlugin implements Plugin<Project> {
                    @Override
                    public void apply(Project project) {
                        project.getTasks().register("bootRun", JavaExec.class);
                        project.getTasks().register("bootTestRun", JavaExec.class);
                        project.getTasks().register("bootJar", Jar.class);
                    }
                }
                """, StandardCharsets.UTF_8);
    }

    private enum Dsl {
        GROOVY(".gradle", '\''),
        KOTLIN(".gradle.kts", '"');

        private final String extension;
        private final char quoteCharacter;

        Dsl(String extension, char quoteCharacter) {
            this.extension = extension;
            this.quoteCharacter = quoteCharacter;
        }

        String quote(String value) {
            String escaped = value.replace("\\", "\\\\").replace(String.valueOf(quoteCharacter), "\\" + quoteCharacter);
            return quoteCharacter + escaped + quoteCharacter;
        }
    }
}
