package space.br1440.platform.tracing.gradle;

import java.util.Set;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.annotations.NotNull;

public final class PlatformTracingJibPlugin implements Plugin<Project> {

    static final String AGENT_GROUP = "space.br1440.platform.starters.tracing";
    static final String AGENT_MODULE = "platform-tracing-javaagent";

    private static final String TASK_GROUP = "platform tracing";
    private static final String CONFIGURATION_NAME = "controlledAgent";
    private static final String STRICT_VERSION_SUFFIX = "!!";
    private static final String AGENT_DIRECTORY = "platform-tracing/agent";
    private static final String LOCAL_AGENT_ARGS_FILE = "platform-tracing/idea-run/local-agent.args";

    private static final String JAVA_PLUGIN_ID = "java";
    private static final String SPRING_BOOT_PLUGIN_ID = "org.springframework.boot";
    private static final String JIB_PLUGIN_ID = "com.google.cloud.tools.jib";
    private static final String CHECK_TASK_NAME = "check";
    private static final Set<String> BOOT_JAVA_EXEC_TASK_NAMES = Set.of("bootRun", "bootTestRun");

    static final String TASK_VERIFY_CONTROLLED_AGENT = "verifyControlledAgent";
    static final String TASK_VERIFY_AGENT_NOT_ON_RUNTIME_CLASSPATH = "verifyAgentNotOnRuntimeClasspath";
    private static final String TASK_WRITE_LOCAL_AGENT_ARG_FILE = "writeLocalAgentArgFile";
    private static final String TASK_PREPARE_CONTROLLED_AGENT = "prepareControlledAgent";
    private static final String TASK_VERIFY_AGENT_NOT_ON_APPLICATION_CLASSPATH = "verifyAgentNotOnApplicationClasspath";

    @Override
    public void apply(@NotNull Project project) {
        ControlledAgentPin pin = ControlledAgentPin.load();
        NamedDomainObjectProvider<Configuration> controlledAgent = registerControlledAgentConfiguration(project, pin);

        Provider<Directory> agentDirectory = project.getLayout().getBuildDirectory().dir(AGENT_DIRECTORY);

        TaskProvider<VerifyControlledAgentTask> verify = registerVerifyTask(project, controlledAgent, agentDirectory, pin);
        Provider<RegularFile> verifiedOutput = verify.flatMap(task -> task.getAgentDirectory().file(task.getAgentFileName()));
        TaskProvider<WriteLocalAgentArgFileTask> argFile = registerArgFileTask(project, verifiedOutput, pin);
        registerPrepareTask(project, verify, argFile);
        TaskProvider<VerifyAgentClasspathTask> runtimeGuard = registerRuntimeClasspathGuard(project);

        project.getPluginManager().withPlugin(JAVA_PLUGIN_ID, applied -> configureClasspathGuard(project));
        project.getPluginManager().withPlugin(SPRING_BOOT_PLUGIN_ID, applied ->
                configureBootRun(project, verifiedOutput, pin, runtimeGuard)
        );
        project.getPluginManager().withPlugin(
                JIB_PLUGIN_ID,
                applied -> JibIntegration.configure(project, verify, verifiedOutput, runtimeGuard, pin)
        );
    }

    private static NamedDomainObjectProvider<Configuration> registerControlledAgentConfiguration(Project project,
                                                                                                 ControlledAgentPin pin) {
        Dependency dependency = project.getDependencies()
                .create(AGENT_GROUP + ":" + AGENT_MODULE + ":" + pin.version() + STRICT_VERSION_SUFFIX);

        String description = """
                Controlled Platform Tracing Java agent; \
                a deployment artifact that never joins an application classpath.""";

        ObjectFactory objects = project.getObjects();
        return project.getConfigurations().register(CONFIGURATION_NAME, configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.setTransitive(false);
            configuration.setDescription(description);
            configuration.attributes(attributes -> {
                attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
                attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
                attributes.attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        objects.named(LibraryElements.class, LibraryElements.JAR)
                );
            });
            configuration.getDependencies().add(dependency);
        });
    }

    private static TaskProvider<VerifyControlledAgentTask> registerVerifyTask(Project project,
                                                                              NamedDomainObjectProvider<Configuration> controlledAgent,
                                                                              Provider<Directory> agentDirectory,
                                                                              ControlledAgentPin pin) {
        return project.getTasks().register(TASK_VERIFY_CONTROLLED_AGENT, VerifyControlledAgentTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Verifies the controlled Java agent against the pinned SHA-256 and copies the verified bytes.");
            task.getControlledAgent().from(controlledAgent);
            task.getAgentVersion().set(pin.version());
            task.getExpectedSha256().set(pin.sha256());
            task.getAgentFileName().set(pin.agentFileName());
            task.getAgentDirectory().set(agentDirectory);
        });
    }

    private static TaskProvider<WriteLocalAgentArgFileTask> registerArgFileTask(Project project,
                                                                                Provider<RegularFile> verifiedOutput,
                                                                                ControlledAgentPin pin) {
        return project.getTasks().register(TASK_WRITE_LOCAL_AGENT_ARG_FILE, WriteLocalAgentArgFileTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Writes the java @argfile with the managed agent flags for local IDE runs.");
            task.getVerifiedAgentJar().set(verifiedOutput);
            task.getExpectedSha256().set(pin.sha256());
            task.getArgsFile().set(project.getLayout().getBuildDirectory().file(LOCAL_AGENT_ARGS_FILE));
        });
    }

    private static void registerPrepareTask(Project project,
                                            TaskProvider<VerifyControlledAgentTask> verify,
                                            TaskProvider<WriteLocalAgentArgFileTask> argFile) {
        project.getTasks().register(TASK_PREPARE_CONTROLLED_AGENT, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Verifies the controlled Java agent and prepares the local argfile.");
            task.dependsOn(verify, argFile);
        });
    }

    private static void configureClasspathGuard(Project project) {
        TaskProvider<VerifyAgentClasspathTask> guard = project.getTasks().register(
                TASK_VERIFY_AGENT_NOT_ON_APPLICATION_CLASSPATH,
                VerifyAgentClasspathTask.class,
                task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Fails when the controlled Java agent leaks into an application classpath.");
                });

        project.getExtensions().getByType(SourceSetContainer.class).configureEach(sourceSet -> guard.configure(task -> {
            task.getClasspathRoots().add(rootComponent(project, sourceSet.getCompileClasspathConfigurationName()));
            task.getClasspathRoots().add(rootComponent(project, sourceSet.getRuntimeClasspathConfigurationName()));
        }));

        project.getTasks().named(TASK_VERIFY_AGENT_NOT_ON_RUNTIME_CLASSPATH, VerifyAgentClasspathTask.class, task ->
                task.getClasspathRoots().add(rootComponent(project, "runtimeClasspath"))
        );
        project.getTasks().named(CHECK_TASK_NAME, task -> task.dependsOn(guard));
        project.getTasks().matching(task -> "bootTestRun".equals(task.getName())).configureEach(task -> task.dependsOn(guard));
    }

    private static TaskProvider<VerifyAgentClasspathTask> registerRuntimeClasspathGuard(Project project) {
        return project.getTasks().register(
                TASK_VERIFY_AGENT_NOT_ON_RUNTIME_CLASSPATH,
                VerifyAgentClasspathTask.class,
                task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Fails when the controlled Java agent leaks into the runtime classpath used by the image or bootRun.");
                });
    }

    private static Provider<ResolvedComponentResult> rootComponent(Project project, String configurationName) {
        return project.getConfigurations()
                .named(configurationName)
                .flatMap(configuration -> configuration.getIncoming().getResolutionResult().getRootComponent());
    }

    private static void configureBootRun(Project project,
                                         Provider<RegularFile> verifiedOutput,
                                         ControlledAgentPin pin,
                                         TaskProvider<VerifyAgentClasspathTask> runtimeGuard) {
        project.getTasks().withType(JavaExec.class).configureEach(task -> {
            if (!BOOT_JAVA_EXEC_TASK_NAMES.contains(task.getName())) {
                return;
            }

            if ("bootRun".equals(task.getName())) {
                task.dependsOn(runtimeGuard);
            }

            ControlledAgentArgumentProvider provider = project.getObjects().newInstance(ControlledAgentArgumentProvider.class);
            provider.getAgentJar().set(verifiedOutput);
            provider.getExpectedSha256().set(pin.sha256());
            task.getJvmArgumentProviders().add(provider);
        });
    }
}
