package space.br1440.platform.leaderelection.leaderelection.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class LeaderRegistry {

    private static final int SCHEDULER_POOL_SIZE = 4;
    private static final long TTL_MILLIS = 30000; // 30 seconds

    private final Map<String, LeaderInfo> leaders = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public LeaderRegistry() {
        this.scheduler = Executors.newScheduledThreadPool(SCHEDULER_POOL_SIZE);
    }

    public LeaderInfo getLeaderInfo(String leadershipName) {
        return leaders.computeIfAbsent(leadershipName, this::createLeaderInfo);
    }

    private LeaderInfo createLeaderInfo(String leadershipName) {
        return new AsyncLeaderInfoProxy(
                leadershipName,
                this,
                scheduler,
                TTL_MILLIS
        );
    }

}
