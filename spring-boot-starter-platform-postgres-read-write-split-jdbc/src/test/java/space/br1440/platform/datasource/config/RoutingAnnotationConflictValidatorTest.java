package space.br1440.platform.datasource.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.annotation.Transactional;
import space.br1440.platform.datasource.postgres.api.routing.UseReadReplica;
import space.br1440.platform.datasource.postgres.internal.proxy.aop.RoutingAnnotationConflictValidator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RoutingAnnotationConflictValidator")
class RoutingAnnotationConflictValidatorTest {

    @Test
    void noConflict_passes() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("cleanService", new CleanService());

        RoutingAnnotationConflictValidator validator = new RoutingAnnotationConflictValidator(factory);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void conflict_throwsIllegalState() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("conflictingService", new ConflictingService());

        RoutingAnnotationConflictValidator validator = new RoutingAnnotationConflictValidator(factory);

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ConflictingService")
                .hasMessageContaining("conflict")
                .hasMessageContaining("@UseReadReplica")
                .hasMessageContaining("@Transactional");
    }

    @Test
    void emptyFactory_passes() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

        RoutingAnnotationConflictValidator validator = new RoutingAnnotationConflictValidator(factory);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    static class CleanService {
        @UseReadReplica
        public void readOnly() {}

        @Transactional
        public void write() {}
    }

    static class ConflictingService {
        @UseReadReplica
        @Transactional
        public void conflict() {}
    }
}
