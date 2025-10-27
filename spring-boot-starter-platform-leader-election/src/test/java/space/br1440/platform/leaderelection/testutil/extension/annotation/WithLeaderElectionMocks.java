package space.br1440.platform.leaderelection.testutil.extension.annotation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.leaderelection.testutil.extension.LeaderElectionTestExtension;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(LeaderElectionTestExtension.class)
@Tag("leaderelection")
@Tag("unit")
public @interface WithLeaderElectionMocks {

}
