package space.br1440.platform.datasource.support.junit;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.MDC;

/**
 * JUnit 5 Extension: гарантирует чистый MDC до и после каждого теста.
 * <p>
 * Дополняет {@link space.br1440.platform.datasource.support.RoutingContextExtension}
 * (последний также чистит {@code DataSourceContextHolder} и Spring's
 * {@code TransactionSynchronizationManager}). Используйте {@code MdcCleanupExtension}
 * в тестах, не работающих с routing-контекстом, чтобы не тащить более широкое поведение.
 */
public class MdcCleanupExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        MDC.clear();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        MDC.clear();
    }
}
