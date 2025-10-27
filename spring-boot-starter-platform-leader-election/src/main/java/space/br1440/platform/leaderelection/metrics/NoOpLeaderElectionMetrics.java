package space.br1440.platform.leaderelection.metrics;

public class NoOpLeaderElectionMetrics implements LeaderElectionMetricsInterface {

    @Override
    public void registerLeadershipGauge(String role, String podName) {
    }

    @Override
    public void updateLeadershipStatus(String role, int status) {
    }

    @Override
    public void recordLeadershipGranted(String role) {
    }

    @Override
    public void recordLeadershipRevoked(String role) {
    }

    @Override
    public void recordFailedAttempt(String role, String reason) {
    }

    @Override
    public void updateLeaseInfo(String role, String holderIdentity, long acquireTime, long renewTime,
                                int leaseDurationSeconds, int leaderTransitions) {
    }

}
