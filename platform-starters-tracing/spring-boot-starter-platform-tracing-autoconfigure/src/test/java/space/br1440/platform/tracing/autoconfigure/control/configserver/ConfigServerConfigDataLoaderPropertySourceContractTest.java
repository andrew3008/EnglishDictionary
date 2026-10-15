package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.Profiles;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.cloud.config.client.ConfigClientProperties;
import org.springframework.cloud.config.client.ConfigServerConfigDataLoader;
import org.springframework.cloud.config.client.ConfigServerConfigDataResource;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingPropertyAdmission;

class ConfigServerConfigDataLoaderPropertySourceContractTest {

    private static final String POLICY_KEY = "platform.tracing.policy.sampling.enabled";

    @Test
    void pinnedConfigClientPrefixesNativeFileLeaf() {
        // given
        org.springframework.cloud.config.environment.Environment remote =
                new org.springframework.cloud.config.environment.Environment("application", "prod");
        remote.add(new org.springframework.cloud.config.environment.PropertySource(
                "file:/config/application-prod.yml",
                Map.of(POLICY_KEY, "true")));
        ConfigClientProperties properties = new ConfigClientProperties(new MockEnvironment());
        ConfigServerConfigDataResource resource = new ConfigServerConfigDataResource(
                properties,
                false,
                mock(Profiles.class));
        ConfigServerConfigDataLoader loader = new StubConfigServerConfigDataLoader(remote);

        // when
        ConfigData configData = loader.doLoad(mock(ConfigDataLoaderContext.class), resource);

        // then
        PropertySource<?> remoteLeaf = configData.getPropertySources().stream()
                .filter(source -> source.containsProperty(POLICY_KEY))
                .findFirst()
                .orElseThrow();
        assertThat(remoteLeaf).isInstanceOf(OriginTrackedMapPropertySource.class);
        assertThat(remoteLeaf.getName()).isEqualTo("configserver:file:/config/application-prod.yml");

        ConfigurableEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(remoteLeaf);

        assertThat(TracingPropertyAdmission.check(environment, TracingControlChannel.CONFIG_SERVER)).isEmpty();
    }

    private static final class StubConfigServerConfigDataLoader extends ConfigServerConfigDataLoader {

        private final Environment remote;

        private StubConfigServerConfigDataLoader(Environment remote) {
            super(new DeferredLogs());
            this.remote = remote;
        }

        @Override
        protected Environment getRemoteEnvironment(ConfigDataLoaderContext context,
                                                   ConfigServerConfigDataResource resource,
                                                   String label,
                                                   String state) {
            return remote;
        }
    }
}
