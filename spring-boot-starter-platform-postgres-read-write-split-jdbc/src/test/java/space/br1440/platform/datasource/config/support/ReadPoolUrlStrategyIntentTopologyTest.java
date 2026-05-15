package space.br1440.platform.datasource.config.support;

import space.br1440.platform.datasource.routing.DataSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties.ReadReplica.Strategy;
import space.br1440.platform.datasource.configuration.support.ConnectParametersMerger;
import space.br1440.platform.datasource.configuration.support.ReadPoolUrlSpec;
import space.br1440.platform.datasource.configuration.support.ReadPoolUrlStrategy;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Закрепляет контракт {@link ReadPoolUrlStrategy} как граница между
 * intent-слоем (DataSourceType.READ) и topology-слоем (targetServerType, loadBalanced).
 *
 * <p>Тест отвечает на три вопроса:
 * <ol>
 *   <li>Какие pgJDBC-параметры попадают в URL read-пула при SECONDARY_ONLY?</li>
 *   <li>Какие — при LOAD_BALANCED?</li>
 *   <li>Что лежит в {@code extraParams} в обоих случаях?</li>
 * </ol>
 *
 * <p>Критично: {@code loadBalanced=true} больше не передаётся через ключ map'а
 * {@code extraParams.loadBalanceHosts}, а является явным булевым полем
 * {@link ReadPoolUrlSpec#loadBalanced()}. JDBC-стартер сам кладёт
 * {@code loadBalanceHosts=true} в URL query-string по этому флагу, R2DBC-стартер —
 * через builder-метод {@code loadBalanceHosts(boolean)}.
 *
 * <p>Пользовательские свойства соединения задаются на уровне общих
 * {@code connectionProperties} ({@code platform.datasource.postgres-cluster.connection-properties.*})
 * и сливаются вызывающей фабрикой через {@link ConnectParametersMerger#merge(Map, Map)} —
 * после {@link ReadPoolUrlStrategy#resolve}.
 */
@DisplayName("ReadPoolUrlStrategy: intent vs topology contract")
class ReadPoolUrlStrategyIntentTopologyTest {

    @Nested
    @DisplayName("SECONDARY_ONLY")
    class SecondaryOnly {

        @Test
        @DisplayName("фиксирует targetServerType=preferSecondary (константа стратегии)")
        void usesPreferSecondaryTargetServerType() {
            PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
            props.getReadReplica().setStrategy(Strategy.SECONDARY_ONLY);

            ReadPoolUrlSpec spec = ReadPoolUrlStrategy.resolve(props);

            assertThat(spec.targetServerType()).isEqualTo("preferSecondary");
        }

        @Test
        @DisplayName("loadBalanced=false и пустые extraParams — secondary не балансируется")
        void doesNotEnableLoadBalancing() {
            PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
            props.getReadReplica().setStrategy(Strategy.SECONDARY_ONLY);

            ReadPoolUrlSpec spec = ReadPoolUrlStrategy.resolve(props);

            assertThat(spec.loadBalanced()).isFalse();
            assertThat(spec.extraParams()).isEmpty();
        }
    }

    @Nested
    @DisplayName("LOAD_BALANCED")
    class LoadBalanced {

        @Test
        @DisplayName("форсирует targetServerType=any")
        void forcesTargetServerTypeAny() {
            PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
            props.getReadReplica().setStrategy(Strategy.LOAD_BALANCED);

            ReadPoolUrlSpec spec = ReadPoolUrlStrategy.resolve(props);

            assertThat(spec.targetServerType()).isEqualTo("any");
        }

        @Test
        @DisplayName("loadBalanced=true — единый явный флаг для обоих транспортов")
        void enablesLoadBalancingViaExplicitFlag() {
            PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
            props.getReadReplica().setStrategy(Strategy.LOAD_BALANCED);

            ReadPoolUrlSpec spec = ReadPoolUrlStrategy.resolve(props);

            assertThat(spec.loadBalanced()).isTrue();
            // extraParams остаётся пустым: ключ loadBalanceHosts JDBC-стартер сам
            // кладёт в URL по spec.loadBalanced(); R2DBC-стартер — во builder.
            assertThat(spec.extraParams()).doesNotContainKey("loadBalanceHosts");
            assertThat(spec.extraParams()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ReadPoolUrlSpec — immutability")
    class SpecImmutability {

        @Test
        @DisplayName("extraParams возвращает иммутабельное представление (Map.copyOf)")
        void extraParamsIsImmutable() {
            Map<String, String> mutable = new LinkedHashMap<>();
            mutable.put("foo", "bar");
            ReadPoolUrlSpec spec = new ReadPoolUrlSpec("primary", false, mutable);

            mutable.put("leaked", "yes");

            assertThat(spec.extraParams()).doesNotContainKey("leaked");
        }

        @Test
        @DisplayName("null extraParams трактуется как пустая карта")
        void nullExtraParamsBecomesEmptyMap() {
            ReadPoolUrlSpec spec = new ReadPoolUrlSpec("primary", false, null);

            assertThat(spec.extraParams()).isEmpty();
        }
    }
}
