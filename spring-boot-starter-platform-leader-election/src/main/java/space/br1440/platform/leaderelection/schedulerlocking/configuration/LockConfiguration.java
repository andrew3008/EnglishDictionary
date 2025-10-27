package space.br1440.platform.leaderelection.schedulerlocking.configuration;

import java.time.Duration;

public record LockConfiguration(
        String leaseRole,
        Duration lockAtMostFor,
        Duration lockAtLeastFor,
        Duration keepAliveLockUntil
) {
}
