package space.br1440.platform.tracing.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;

final class JibAgentIntegration {

    private static final String JIB_PLUGIN_ID = "com.google.cloud.tools.jib";
    private static final String TASK_GROUP = "platform tracing";
    private static final String TASK_STAGE_CONTROLLED_AGENT_FOR_JIB = "stageControlledAgentForJib";
    private static final String TASK_VERIFY_JIB_LAUNCH_CONTRACT = "verifyJibLaunchContract";
    private static final String STAGING_DIRECTORY = "platform-tracing/jib-agent";
    private static final String STAGED_AGENT_FILE_NAME = "agent.jar";
    private static final String IMAGE_AGENT_DIRECTORY = "/opt/platform-tracing";
    private static final String IMAGE_AGENT_PATH = IMAGE_AGENT_DIRECTORY + "/" + STAGED_AGENT_FILE_NAME;

    private static final String JMX_REMOTE_DECLARATION_PREFIX = "platform.tracing.jmx.remote.";
    private static final String JMX_MODE_PROPERTY = JMX_REMOTE_DECLARATION_PREFIX + "mode";
    private static final String JMX_LISTENER_CONFIRMED_PROPERTY = JMX_REMOTE_DECLARATION_PREFIX + "listener-confirmed";
    private static final String JMX_AUTHORIZATION_FORWARDER_PROPERTY = JMX_REMOTE_DECLARATION_PREFIX + "authorization-forwarder-installed";
    private static final String JMX_UNCERTIFIED_STARTUP_PREFIX = "com.sun.management.jmxremote";
    private static final String DISABLE_ATTACH_MECHANISM_FLAG = "-XX:+DisableAttachMechanism";
    private static final String ENABLE_ATTACH_MECHANISM_FLAG = "-XX:-DisableAttachMechanism";

    private static final List<String> MANAGED_OTEL_PROPERTY_KEYS = List.of(
            "otel.propagators",
            "otel.exporter.otlp.traces.endpoint",
            "otel.exporter.otlp.traces.protocol",
            "otel.traces.exporter",
            "otel.metrics.exporter",
            "otel.logs.exporter",
            "otel.service.name",
            "otel.resource.attributes",
            "otel.javaagent.configuration-file",
            "otel.experimental.config.file",
            "otel.sdk.disabled",
            "otel.javaagent.enabled"
    );

    private static final List<String> JIB_IMAGE_BUILD_TASK_NAMES = List.of("jib", "jibDockerBuild", "jibBuildTar");

    private JibAgentIntegration() {
    }

    static void configureIfJibIsApplied(Project project,
                                        TaskProvider<VerifyControlledAgentTask> verify,
                                        Provider<RegularFile> verifiedAgentJar,
                                        ControlledAgentPin pin) {
        project.getPluginManager().withPlugin(
                JIB_PLUGIN_ID,
                appliedPlugin -> configure(project, verify, verifiedAgentJar, pin)
        );
    }

    private static void configure(Project project,
                                  TaskProvider<VerifyControlledAgentTask> verify,
                                  Provider<RegularFile> verifiedAgentJar,
                                  ControlledAgentPin pin) {
        Provider<Directory> stagingDirectory = project.getLayout().getBuildDirectory().dir(STAGING_DIRECTORY);
        TaskProvider<Sync> stage = registerStagingTask(project, verify, verifiedAgentJar, stagingDirectory);
        List<String> managedJvmFlags = managedJvmFlags(pin);
        JibExtensionAccess jib = JibExtensionAccess.of(project);

        project.afterEvaluate(evaluatedProject -> {
            mergeJvmFlags(jib, managedJvmFlags);
            appendAgentDirectory(evaluatedProject, jib, stagingDirectory);
        });

        TaskProvider<VerifyJibLaunchContractTask> launchContract = registerLaunchContractTask(
                project, stage, stagingDirectory, jib, managedJvmFlags
        );

        JIB_IMAGE_BUILD_TASK_NAMES.forEach(taskName -> project.getTasks().named(
                taskName, task -> task.dependsOn(launchContract))
        );
    }

    private static TaskProvider<VerifyJibLaunchContractTask> registerLaunchContractTask(Project project,
                                                                                        TaskProvider<Sync> stage,
                                                                                        Provider<Directory> stagingDirectory,
                                                                                        JibExtensionAccess jib,
                                                                                        List<String> managedJvmFlags) {
        return project.getTasks().register(
                TASK_VERIFY_JIB_LAUNCH_CONTRACT,
                VerifyJibLaunchContractTask.class,
                task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Fails the image build when the Jib launch contract was overwritten.");
                    task.dependsOn(stage);
                    task.getStagedAgentJar().set(stagingDirectory.map(directory -> directory.file(STAGED_AGENT_FILE_NAME)));
                    task.getRequiredJvmFlags().set(managedJvmFlags);
                    task.getForbiddenJvmFlags().set(List.of(ENABLE_ATTACH_MECHANISM_FLAG));
                    task.getForbiddenSystemPropertyKeyPrefixes().set(List.of(JMX_UNCERTIFIED_STARTUP_PREFIX));
                    task.getManagedSystemPropertyKeys().set(MANAGED_OTEL_PROPERTY_KEYS);
                    task.getRequiredAgentDestination().set(IMAGE_AGENT_DIRECTORY);
                    task.getJvmFlags().set(project.provider(jib::jvmFlags));
                    task.getEntrypoint().set(project.provider(jib::entrypoint));
                    task.getEnvironment().set(project.provider(jib::environment));
                    task.getExtraDirectoryDestinations().set(project.provider(jib::extraDirectoryDestinations));
                    task.getPluginExtensionImplementations().set(project.provider(jib::pluginExtensionImplementations));
                });
    }

    private static TaskProvider<Sync> registerStagingTask(Project project,
                                                          TaskProvider<VerifyControlledAgentTask> verify,
                                                          Provider<RegularFile> verifiedAgentJar,
                                                          Provider<Directory> stagingDirectory) {
        return project.getTasks().register(TASK_STAGE_CONTROLLED_AGENT_FOR_JIB, Sync.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Stages the verified controlled Java agent under a stable file name for Jib.");
            task.dependsOn(verify);
            task.from(verifiedAgentJar);
            task.into(stagingDirectory);
            task.rename(sourceName -> STAGED_AGENT_FILE_NAME);
        });
    }

    private static List<String> managedJvmFlags(ControlledAgentPin pin) {
        return List.of(
                "-javaagent:" + IMAGE_AGENT_PATH,
                "-D" + ControlledAgentSystemProperties.EXPECTED_SHA256_PROPERTY + "=" + pin.sha256(),
                "-D" + JMX_MODE_PROPERTY + "=REMOTE_PROHIBITED",
                "-D" + JMX_LISTENER_CONFIRMED_PROPERTY + "=false",
                "-D" + JMX_AUTHORIZATION_FORWARDER_PROPERTY + "=false",
                DISABLE_ATTACH_MECHANISM_FLAG
        );
    }

    private static void mergeJvmFlags(JibExtensionAccess jib, List<String> managedJvmFlags) {
        List<String> combined = new ArrayList<>(jib.jvmFlags());
        combined.addAll(managedJvmFlags);
        jib.setJvmFlags(combined);
    }

    private static void appendAgentDirectory(Project project,
                                             JibExtensionAccess jib,
                                             Provider<Directory> stagingDirectory) {
        if (jib.onlyImplicitDefaultExtraDirectory(project)) {
            jib.addExtraDirectory(JibExtensionAccess.implicitJibExtraDirectory(project).toFile(), "/");
        }

        File stagingDirectoryFile = stagingDirectory.get().getAsFile();
        jib.addExtraDirectory(stagingDirectoryFile, IMAGE_AGENT_DIRECTORY);
    }
}
