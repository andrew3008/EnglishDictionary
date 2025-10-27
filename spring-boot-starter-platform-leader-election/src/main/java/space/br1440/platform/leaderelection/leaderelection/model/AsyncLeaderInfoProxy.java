package space.br1440.platform.leaderelection.leaderelection.model;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AsyncLeaderInfoProxy implements LeaderInfo {

    private final String leadershipName;
    private final LeaderRegistry leaderRegistry;
    private final ScheduledExecutorService scheduler;
    private final long ttlMillis;
    private volatile LeaderInfo delegate;

    public AsyncLeaderInfoProxy(String leadershipName,
                                LeaderRegistry leaderRegistry,
                                ScheduledExecutorService scheduler,
                                long ttlMillis) {
        this.leadershipName = leadershipName;
        this.leaderRegistry = leaderRegistry;
        this.scheduler = scheduler;
        this.ttlMillis = ttlMillis;

        refreshLeaderInfoSync();
        scheduleRefresh();
    }
    
    private void refreshLeaderInfoSync() {
        try {
            delegate = leaderRegistry.getLeaderInfo(leadershipName);
        } catch (Exception ex) {
            log.error("Failed to initialize leader info for role [{}]", leadershipName, ex);
        }
    }

    @Override
    public String getLeadershipName() {
        return leadershipName;
    }

    @Override
    public boolean isLeader() {
        try {
            return (delegate != null) && delegate.isLeader();
        } catch (Exception ex) {
            log.error("Error checking leadership for role [{}]", leadershipName, ex);
            return false;
        }
    }

    @Override
    public String getCurrentLeader() {
        try {
            return (delegate != null) ? delegate.getCurrentLeader() : null;
        } catch (Exception ex) {
            log.error("Error retrieving current leader for role [{}]", leadershipName, ex);
            return null;
        }
    }

    public void refreshLeaderInfoWithRetry() {
        scheduler.execute(() -> {
            try {
                delegate = leaderRegistry.getLeaderInfo(leadershipName);
                log.debug("Refreshed leader info for role [{}]: {}", leadershipName, delegate.getCurrentLeader());
            } catch (Exception ex) {
                log.error("Failed to refresh leader info for role [{}]", leadershipName, ex);
            }
        });
    }

    private void scheduleRefresh() {
        if (ttlMillis > 0) {
            scheduler.scheduleAtFixedRate(this::refreshLeaderInfoWithRetry, ttlMillis, ttlMillis, TimeUnit.MILLISECONDS);
        }
    }

}
