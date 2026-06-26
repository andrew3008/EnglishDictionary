package space.br1440.platform.datasource.it;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.postgres.api.exception.WriteDataSourceUnavailableException;
import space.br1440.platform.datasource.postgres.internal.proxy.core.PasswordExpiryRetryDataSourceProxy;
import space.br1440.platform.datasource.support.ProductionDataSourceChainSupport;
import space.br1440.platform.datasource.support.ProductionDataSourceChainSupport.ProductionChain;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.datasource.support.ProductionDataSourceChainSupport.createPool;
import static space.br1440.platform.datasource.support.ProductionDataSourceChainSupport.executeSelect1;

/**
 * Эмпирическая верификация PR 4.1: после переноса {@link PasswordExpiryRetryDataSourceProxy}
 * под {@link org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy} (порядок
 * {@code Lazy → Routing → PwdRetry → Hikari}) retry на SQLSTATE 28P01 срабатывает в prod-цепочке
 * через {@code @Primary dataSource}.
 *
 * <p>Контрольный тест (direct proxy) подтверждает механику retry без Lazy. Prod-chain тесты
 * доказывают: (1) при ротации без обновления Hikari-пароля retry срабатывает (callback >= 1),
 * но восстановления нет — стартер не делает refresh credentials; (2) при внешнем обновлении
 * Hikari-пароля (ESO/Vault Agent) цепочка восстанавливается.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
@DisplayName("PasswordExpiryRetryDataSourceProxy × живой PostgreSQL")
class PasswordRotationClusterIT {

    private static final String ROT_USER = "rotuser";
    private static final String INITIAL_PWD = "secret1";
    private static final String NEW_PWD = "secret2";

    private PgCluster cluster;
    private String jdbcUrl;

    @BeforeAll
    void prepareUser(PgCluster cluster) throws Exception {
        this.cluster = cluster;
        this.jdbcUrl = "jdbc:postgresql://" + cluster.primaryHostPort() + "/" + cluster.dbName()
                + "?targetServerType=primary";

        execPsql("CREATE ROLE " + ROT_USER + " WITH LOGIN PASSWORD '" + INITIAL_PWD + "';");
        execPsql("GRANT ALL PRIVILEGES ON DATABASE " + cluster.dbName() + " TO " + ROT_USER + ";");
    }

    @AfterAll
    void cleanup() throws Exception {
        execPsql("DROP OWNED BY " + ROT_USER + " CASCADE;");
        execPsql("DROP ROLE IF EXISTS " + ROT_USER + ";");
    }

    @BeforeEach
    void resetRotUserPassword() throws Exception {
        execPsql("ALTER ROLE " + ROT_USER + " WITH PASSWORD '" + INITIAL_PWD + "';");
    }

    @Nested
    @DisplayName("Контроль: PwdRetry(Hikari) без Lazy")
    class DirectProxyControl {

        @Test
        @DisplayName("28P01 после ротации: direct proxy сбрасывает пул и переподключается")
        void rotation_triggersCallback_andRetrySucceeds() throws Exception {
            AtomicInteger callbackInvocations = new AtomicInteger();

            try (HikariDataSource hds = createPool(jdbcUrl, ROT_USER, INITIAL_PWD, false)) {
                executeSelect1(hds);

                rotatePasswordAndEvictPools(hds);

                PasswordExpiryRetryDataSourceProxy proxy = new PasswordExpiryRetryDataSourceProxy(hds, () -> {
                    callbackInvocations.incrementAndGet();
                    hds.getHikariConfigMXBean().setPassword(NEW_PWD);
                    hds.getHikariPoolMXBean().softEvictConnections();
                });

                executeSelect1(proxy);

                assertThat(callbackInvocations.get())
                        .as("direct proxy must invoke pool-reset callback on 28P01 at getConnection()")
                        .isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("Prod-цепочка: Lazy(Routing(PwdRetry(Hikari)))")
    class ProductionChainRetry {

        @Test
        @DisplayName("ротация без refresh пароля: PwdRetry срабатывает (callback >= 1), но восстановления нет")
        void rotationWithoutRefresh_triggersRetryButFails() throws Exception {
            try (ProductionChain chain = ProductionDataSourceChainSupport.buildProductionChain(
                    jdbcUrl, ROT_USER, INITIAL_PWD)) {

                executeSelect1(chain.primaryDataSource());

                // ALTER ROLE + soft-evict, но Hikari-пароль НЕ обновляется (стартер не делает refresh).
                rotatePasswordAndEvictPools(chain.writePool(), chain.readPool());

                assertThatThrownBy(() -> executeSelect1(chain.primaryDataSource()))
                        .as("без обновления Hikari-пароля retry повторно падает на 28P01")
                        .satisfies(PasswordRotationClusterIT.this::assert28P01OrWriteUnavailable);

                assertThat(chain.callbackInvocations().get())
                        .as("PwdRetry под Lazy теперь перехватывает 28P01 в prod-цепочке (регрессионный детектор)")
                        .isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("ротация + внешний refresh Hikari-пароля: цепочка восстанавливается")
        void rotationWithRefresh_recovers() throws Exception {
            try (ProductionChain chain = ProductionDataSourceChainSupport.buildProductionChain(
                    jdbcUrl, ROT_USER, INITIAL_PWD)) {

                executeSelect1(chain.primaryDataSource());

                execPsql("ALTER ROLE " + ROT_USER + " WITH PASSWORD '" + NEW_PWD + "';");

                // Симуляция ESO/Vault Agent: новый секрет материализован в конфигурацию Hikari.
                refreshHikariPassword(chain.writePool(), chain.readPool());

                // Новые физические соединения открываются уже с актуальным паролем.
                executeSelect1(chain.primaryDataSource());
            }
        }
    }

    private void rotatePasswordAndEvictPools(HikariDataSource... pools) throws Exception {
        execPsql("ALTER ROLE " + ROT_USER + " WITH PASSWORD '" + NEW_PWD + "';");
        for (HikariDataSource pool : pools) {
            pool.getHikariPoolMXBean().softEvictConnections();
        }
    }

    private void refreshHikariPassword(HikariDataSource... pools) {
        for (HikariDataSource pool : pools) {
            pool.getHikariConfigMXBean().setPassword(NEW_PWD);
            pool.getHikariPoolMXBean().softEvictConnections();
        }
    }

    /**
     * Толерантный assert (R2): тип исключения зависит от того, обернёт ли Hikari auth-сбой
     * в {@code SQLTransientConnectionException}. Принимаем {@code WriteDataSourceUnavailableException}
     * ИЛИ наличие SQLSTATE 28P01 в causal chain.
     */
    private void assert28P01OrWriteUnavailable(Throwable ex) {
        boolean writeUnavailable = ex instanceof WriteDataSourceUnavailableException;
        boolean invalidPassword = "28P01".equalsIgnoreCase(
                ProductionDataSourceChainSupport.extractSqlState(ex));
        assertThat(writeUnavailable || invalidPassword)
                .as("ожидался WriteDataSourceUnavailableException или SQLSTATE 28P01 в цепочке причин, получено: %s", ex)
                .isTrue();
    }

    private void execPsql(String sql) throws Exception {
        cluster.execPsql(sql);
    }
}
