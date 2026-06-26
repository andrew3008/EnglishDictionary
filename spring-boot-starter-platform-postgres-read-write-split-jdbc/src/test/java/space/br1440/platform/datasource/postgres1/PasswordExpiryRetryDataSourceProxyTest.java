package space.br1440.platform.datasource.postgres.internal.proxy.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("PasswordExpiryRetryDataSourceProxy")
class PasswordExpiryRetryDataSourceProxyTest {

    @Test
    @DisplayName("28P01 в lowercase SQLSTATE: retry срабатывает (equalsIgnoreCase)")
    void retriesOnce_when28p01Lowercase_andSucceeds() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection expected = mock(Connection.class);
        AtomicInteger calls = new AtomicInteger(0);
        when(delegate.getConnection()).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new SQLException("password authentication failed", "28p01");
            }
            return expected;
        });

        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate);

        Connection result = proxy.getConnection();

        assertThat(result).isSameAs(expected);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("poolResetCallback падает: retry всё равно выполняется и может успешно подключиться")
    void retriesOnce_whenPoolResetCallbackFails_andSucceeds() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection expected = mock(Connection.class);
        AtomicInteger calls = new AtomicInteger(0);
        when(delegate.getConnection()).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new SQLException("password authentication failed", "28P01");
            }
            return expected;
        });

        AtomicInteger callbackAttempts = new AtomicInteger();
        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate, () -> {
            callbackAttempts.incrementAndGet();
            throw new RuntimeException("soft-evict failed");
        });

        Connection result = proxy.getConnection();

        assertThat(result).isSameAs(expected);
        assertThat(callbackAttempts).hasValue(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("poolResetCallback падает: при повторном 28P01 исключение пробрасывается")
    void throwsOn28P01_whenPoolResetCallbackFails_andRetryAlsoFails() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenThrow(
                new SQLException("password authentication failed", "28P01"));

        AtomicInteger callbackAttempts = new AtomicInteger();
        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate, () -> {
            callbackAttempts.incrementAndGet();
            throw new RuntimeException("soft-evict failed");
        });

        assertThatThrownBy(proxy::getConnection)
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("28P01"));
        assertThat(callbackAttempts).hasValue(1);
        verify(delegate, times(2)).getConnection();
    }

    @Test
    void successfulConnectionPassesThrough() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection expected = mock(Connection.class);
        when(delegate.getConnection()).thenReturn(expected);

        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate);
        Connection result = proxy.getConnection();

        assertThat(result).isSameAs(expected);
        verify(delegate, times(1)).getConnection();
    }

    @Test
    void retriesOnce_when28P01_andSucceeds() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection expected = mock(Connection.class);
        AtomicInteger calls = new AtomicInteger(0);
        when(delegate.getConnection()).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new SQLException("password authentication failed", "28P01");
            }
            return expected;
        });

        AtomicBoolean resetCalled = new AtomicBoolean(false);
        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate, () -> resetCalled.set(true));

        Connection result = proxy.getConnection();

        assertThat(result).isSameAs(expected);
        assertThat(resetCalled).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void propagatesNon28P01Exceptions() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenThrow(
                new SQLException("connection refused", "08001"));

        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate);

        assertThatThrownBy(proxy::getConnection)
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("08001"));
        verify(delegate, times(1)).getConnection();
    }

    @Test
    void throwsOn28P01_ifRetryAlsoFails() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenThrow(
                new SQLException("password authentication failed", "28P01"));

        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate);

        assertThatThrownBy(proxy::getConnection)
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("28P01"));
        verify(delegate, times(2)).getConnection();
    }

    @Test
    void retriesOn28P01_whenWrappedInRuntimeException() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection expected = mock(Connection.class);
        AtomicInteger calls = new AtomicInteger(0);
        when(delegate.getConnection()).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                SQLException inner = new SQLException("auth failed", "28P01");
                throw new SQLException("wrapper", inner);
            }
            return expected;
        });

        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate);
        Connection result = proxy.getConnection();

        assertThat(result).isSameAs(expected);
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetry_whenNoSqlState() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenThrow(new SQLException("generic error"));

        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate);

        assertThatThrownBy(proxy::getConnection).isInstanceOf(SQLException.class);
        verify(delegate, times(1)).getConnection();
    }

    @Test
    void getConnectionWithUsernamePassword_retriesOn28P01() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection expected = mock(Connection.class);
        AtomicInteger calls = new AtomicInteger(0);
        when(delegate.getConnection("user", "pass")).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new SQLException("password authentication failed", "28P01");
            }
            return expected;
        });

        var proxy = new PasswordExpiryRetryDataSourceProxy(delegate);
        Connection result = proxy.getConnection("user", "pass");

        assertThat(result).isSameAs(expected);
        assertThat(calls).hasValue(2);
    }
}
