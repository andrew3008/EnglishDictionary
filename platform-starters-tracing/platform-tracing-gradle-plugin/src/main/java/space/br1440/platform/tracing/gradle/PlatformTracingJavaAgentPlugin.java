package space.br1440.platform.tracing.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class PlatformTracingJavaAgentPlugin implements Plugin<Project> {

    static final String AGENT_GROUP = "space.br1440.platform.starters.tracing";
    static final String AGENT_MODULE = "platform-tracing-javaagent";

    private static final String TASK_GROUP = "platform tracing";
    private static final String CONFIGURATION_NAME = "controlledAgent";
    private static final String AGENT_DIRECTORY = "platform-tracing/agent";
    private static final String DOCKER_BUILD_ARGS_FILE = "platform-tracing/docker-build-args.env";

    private static final String JAVA_PLUGIN_ID = "java";
    private static final String SPRING_BOOT_PLUGIN_ID = "org.springframework.boot";
    private static final String BOOT_RUN_TASK_NAME = "bootRun";
    private static final String CHECK_TASK_NAME = "check";
    private static final List<String> APPLICATION_CLASSPATH_CONFIGURATIONS = List.of("compileClasspath", "runtimeClasspath");

    private static final String TASK_MATERIALIZE_CONTROLLED_AGENT = "materializeControlledAgent";
    private static final String TASK_VERIFY_CONTROLLED_AGENT = "verifyControlledAgent";
    private static final String TASK_GENERATE_AGENT_DOCKER_BUILD_ARGS = "generateAgentDockerBuildArgs";
    private static final String TASK_PREPARE_CONTROLLED_AGENT = "prepareControlledAgent";
    private static final String TASK_VERIFY_AGENT_NOT_ON_APPLICATION_CLASSPATH = "verifyAgentNotOnApplicationClasspath";

    @Override
    public void apply(@NotNull Project project) {
        ControlledAgentPin pin = ControlledAgentPin.load();
        Configuration controlledAgent = createControlledAgentConfiguration(project, pin);
        AgentArtifact agentArtifact = AgentArtifact.resolve(project, pin);

        TaskProvider<Sync> materialize = registerMaterializeTask(project, controlledAgent, agentArtifact);
        TaskProvider<VerifyControlledAgentTask> verify = registerVerifyTask(project, materialize, agentArtifact, pin);
        TaskProvider<GenerateAgentDockerBuildArgsTask> dockerBuildArgs = registerDockerBuildArgsTask(project, verify, agentArtifact);
        registerPrepareTask(project, verify, dockerBuildArgs);

        project.getPluginManager().withPlugin(JAVA_PLUGIN_ID, applied -> configureClasspathGuard(project));
        project.getPluginManager().withPlugin(SPRING_BOOT_PLUGIN_ID, applied -> configureBootRun(project, verify, agentArtifact.jar(), pin));
        JibAgentIntegration.configureIfJibIsApplied(project, verify, agentArtifact.jar(), pin);
    }

    private static Configuration createControlledAgentConfiguration(Project project,
                                                                    ControlledAgentPin pin) {
        ExternalModuleDependency dependency = (ExternalModuleDependency) project
                .getDependencies()
                .create(AGENT_GROUP + ":" + AGENT_MODULE + ":" + pin.version());

        dependency.version(constraint -> constraint.strictly(pin.version()));
        dependency.setTransitive(false);

        Configuration controlledAgent = project.getConfigurations().create(
                CONFIGURATION_NAME, configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                    configuration.setTransitive(false);
                    configuration.setDescription(
                            "Controlled Platform Tracing Java agent; a deployment artifact that "
                                    + "never joins an application classpath.");
                });
        controlledAgent.getDependencies().add(dependency);
        return controlledAgent;
    }

    private static TaskProvider<Sync> registerMaterializeTask(Project project,
                                                              Configuration controlledAgent,
                                                              AgentArtifact agentArtifact) {
        return project.getTasks().register(
                TASK_MATERIALIZE_CONTROLLED_AGENT,
                Sync.class,
                task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Copies the controlled Java agent into the build directory.");
                    task.from(controlledAgent);
                    task.into(agentArtifact.directory());

                    String fileName = agentArtifact.fileName();
                    task.rename(sourceName -> fileName);
                });
    }

    private static TaskProvider<VerifyControlledAgentTask> registerVerifyTask(Project project,
                                                                              TaskProvider<Sync> materialize,
                                                                              AgentArtifact agentArtifact,
                                                                              ControlledAgentPin pin) {
        return project.getTasks().register(
                TASK_VERIFY_CONTROLLED_AGENT, VerifyControlledAgentTask.class, task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Verifies the controlled Java agent against the pinned SHA-256.");
                    task.dependsOn(materialize);
                    task.getAgentJar().set(agentArtifact.jar());
                    task.getAgentVersion().set(pin.version());
                    task.getExpectedSha256().set(pin.sha256());
                });
    }

    private static TaskProvider<GenerateAgentDockerBuildArgsTask> registerDockerBuildArgsTask(Project project,
                                                                                              TaskProvider<VerifyControlledAgentTask> verify,
                                                                                              AgentArtifact agentArtifact) {
        return project.getTasks().register(
                TASK_GENERATE_AGENT_DOCKER_BUILD_ARGS, GenerateAgentDockerBuildArgsTask.class, task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Writes the Docker build argument that pins the agent file name.");
                    task.dependsOn(verify);
                    task.getAgentFileName().set(agentArtifact.fileName());
                    task.getBuildArgsFile().set(
                            project.getLayout().getBuildDirectory().file(DOCKER_BUILD_ARGS_FILE)
                    );
                });
    }

    private static void registerPrepareTask(Project project,
                                            TaskProvider<VerifyControlledAgentTask> verify,
                                            TaskProvider<GenerateAgentDockerBuildArgsTask> dockerBuildArgs) {
        project.getTasks().register(TASK_PREPARE_CONTROLLED_AGENT, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Prepares the verified controlled Java agent for the image build.");
            task.dependsOn(verify, dockerBuildArgs);
        });
    }

    private static void configureClasspathGuard(Project project) {
        TaskProvider<VerifyAgentClasspathTask> guard = project.getTasks().register(
                TASK_VERIFY_AGENT_NOT_ON_APPLICATION_CLASSPATH,
                VerifyAgentClasspathTask.class,
                task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Fails when the controlled Java agent leaks into an application classpath.");
                    APPLICATION_CLASSPATH_CONFIGURATIONS.forEach(
                            configurationName -> task.getClasspathRoots()
                                    .add(rootComponent(project, configurationName))
                    );
                });

        project.getTasks().named(CHECK_TASK_NAME, task -> task.dependsOn(guard));
    }

    private static Provider<ResolvedComponentResult> rootComponent(Project project,
                                                                   String configurationName) {
        return project.getConfigurations()
                .getByName(configurationName)
                .getIncoming()
                .getResolutionResult()
                .getRootComponent();
    }

    private static void configureBootRun(Project project,
                                         TaskProvider<VerifyControlledAgentTask> verify,
                                         Provider<RegularFile> agentJar,
                                         ControlledAgentPin pin) {
        project.getTasks().withType(JavaExec.class).named(BOOT_RUN_TASK_NAME).configure(task -> {
            task.dependsOn(verify);

            ControlledAgentArgumentProvider provider = project.getObjects().newInstance(ControlledAgentArgumentProvider.class);
            provider.getAgentJar().set(agentJar);
            provider.getExpectedSha256().set(pin.sha256());
            task.getJvmArgumentProviders().add(provider);
        });
    }

    private record AgentArtifact(String fileName, Provider<Directory> directory, Provider<RegularFile> jar) {

        static AgentArtifact resolve(Project project, ControlledAgentPin pin) {
            String fileName = AGENT_MODULE + "-" + pin.version() + ".jar";

            Provider<Directory> directory = project.getLayout().getBuildDirectory().dir(AGENT_DIRECTORY);
            Provider<RegularFile> jar = directory.map(dir -> dir.file(fileName));
            return new AgentArtifact(fileName, directory, jar);
        }
    }
}
