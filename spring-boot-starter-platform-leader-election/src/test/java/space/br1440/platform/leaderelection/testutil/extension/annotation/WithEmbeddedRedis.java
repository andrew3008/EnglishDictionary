package space.br1440.platform.leaderelection.testutil.extension.annotation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.leaderelection.testutil.extension.LeaderElectionTestExtension;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(LeaderElectionTestExtension.class)
@Tag("integration")
@Tag("redis")
public @interface WithEmbeddedRedis {

    /**
     * Порт для запуска Embedded Redis.
     * <b>По умолчанию 0:</b> автоматический выбор свободного порта операционной системой.
     */
    int redisPort() default 0;

    /**
     * Очищать ли Redis перед каждым тестом.
     */
    boolean cleanupBeforeEach() default true;

}
