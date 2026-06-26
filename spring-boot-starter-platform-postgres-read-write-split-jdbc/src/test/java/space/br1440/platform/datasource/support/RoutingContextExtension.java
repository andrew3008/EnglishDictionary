package space.br1440.platform.datasource.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;

/**
 * JUnit 5 Extension: гарантированная очистка ThreadLocal-контекстов после каждого теста.
 * <p>
 * Устраняет необходимость в {@code @AfterEach cleanup()} в тестовых классах, работающих
 * с {@link DataSourceContextHolder}, {@link MDC} или {@link TransactionSynchronizationManager}.
 *
 * <pre>{@code
 * @ExtendWith(RoutingContextExtension.class)
 * class MyTest { ... }
 * }</pre>
 */
public class RoutingContextExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        // Полностью сбрасываем стек — важно для leak-тестов с вложенными set/clear
        while (DataSourceContextHolder.currentIfSet() != null) {
            DataSourceContextHolder.clear();
        }
        MDC.clear();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.clear();
    }
}
