package space.br1440.platform.datasource.support.assertj;

import com.zaxxer.hikari.HikariDataSource;
import org.assertj.core.api.AbstractAssert;

/**
 * Fluent AssertJ для проверки конфигурации {@link HikariDataSource} (read-only / pool name / JDBC URL / sizing).
 */
public class HikariDataSourceAssert extends AbstractAssert<HikariDataSourceAssert, HikariDataSource> {

    public HikariDataSourceAssert(HikariDataSource ds) {
        super(ds, HikariDataSourceAssert.class);
    }

    public HikariDataSourceAssert isReadOnly() {
        isNotNull();
        if (!actual.isReadOnly()) {
            failWithMessage("Expected HikariDataSource <%s> to be read-only but it was not", actual.getPoolName());
        }
        return this;
    }

    public HikariDataSourceAssert isNotReadOnly() {
        isNotNull();
        if (actual.isReadOnly()) {
            failWithMessage(
                    "Expected HikariDataSource <%s> NOT to be read-only but it was",
                    actual.getPoolName());
        }
        return this;
    }

    public HikariDataSourceAssert hasPoolName(String expected) {
        isNotNull();
        if (!expected.equals(actual.getPoolName())) {
            failWithMessage("Expected pool name <%s> but was <%s>", expected, actual.getPoolName());
        }
        return this;
    }

    public HikariDataSourceAssert hasMaximumPoolSize(int expected) {
        isNotNull();
        if (actual.getMaximumPoolSize() != expected) {
            failWithMessage(
                    "Expected maximumPoolSize <%d> but was <%d>",
                    expected, actual.getMaximumPoolSize());
        }
        return this;
    }

    public HikariDataSourceAssert hasMinimumIdle(int expected) {
        isNotNull();
        if (actual.getMinimumIdle() != expected) {
            failWithMessage("Expected minimumIdle <%d> but was <%d>", expected, actual.getMinimumIdle());
        }
        return this;
    }

    public HikariDataSourceAssert hasConnectionTestQueryContaining(String fragment) {
        isNotNull();
        String query = actual.getConnectionTestQuery();
        if (query == null || !query.contains(fragment)) {
            failWithMessage(
                    "Expected connectionTestQuery to contain <%s> but was <%s>",
                    fragment, query);
        }
        return this;
    }

    public HikariDataSourceAssert hasConnectionInitSqlContaining(String fragment) {
        isNotNull();
        String sql = actual.getConnectionInitSql();
        if (sql == null || !sql.contains(fragment)) {
            failWithMessage(
                    "Expected connectionInitSql to contain <%s> but was <%s>",
                    fragment, sql);
        }
        return this;
    }

    public HikariDataSourceAssert hasKeepaliveTime(long expectedMs) {
        isNotNull();
        if (actual.getKeepaliveTime() != expectedMs) {
            failWithMessage(
                    "Expected keepaliveTime <%d ms> but was <%d ms>",
                    expectedMs, actual.getKeepaliveTime());
        }
        return this;
    }

    /**
     * Возвращает HikariUrlAssert для chained проверок JDBC URL.
     */
    public HikariUrlAssert jdbcUrl() {
        isNotNull();
        return new HikariUrlAssert(actual.getJdbcUrl());
    }
}
