package space.br1440.platform.leaderelection.util;

import lombok.experimental.UtilityClass;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;

@UtilityClass
public final class LeadershipNameResolver {

    public static String resolve(LeaderElectionProperties properties, Environment environment) {
        String leadershipName = properties.getLeadership().getLeadershipName();
        if (StringUtils.hasText(leadershipName)) {
            return leadershipName;
        }

        String applicationName = environment.getProperty("spring.application.name");
        if (StringUtils.hasText(applicationName)) {
            return applicationName;
        }

        throw new IllegalStateException(
                "Leadership name is not configured. Please set either " +
                "'platform.leader-election.leadership.leadership-name' or " +
                "'spring.application.name' property"
        );
    }

}

