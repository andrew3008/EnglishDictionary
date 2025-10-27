package space.br1440.platform.leaderelection.configuration.condition;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;

public class RedisRequiredCondition implements Condition {

    @Override
    public boolean matches(@NotNull ConditionContext context, @NotNull AnnotatedTypeMetadata metadata) {
        Boolean leadershipEnabled = context.getEnvironment()
                .getProperty(LeaderElectionProperties.PREFIX + ".leadership.enabled", Boolean.class, false);

        Boolean lockEnabled = context.getEnvironment()
                .getProperty(LeaderElectionProperties.PREFIX + ".lock.enabled", Boolean.class, false);

        return leadershipEnabled || lockEnabled;
    }

}

