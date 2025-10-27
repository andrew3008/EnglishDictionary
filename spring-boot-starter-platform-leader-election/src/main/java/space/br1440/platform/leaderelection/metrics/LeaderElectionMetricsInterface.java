package space.br1440.platform.leaderelection.metrics;

public interface LeaderElectionMetricsInterface {

    void registerLeadershipGauge(String role, String podName);

    void updateLeadershipStatus(String role, int status);

    void recordLeadershipGranted(String role);

    void recordLeadershipRevoked(String role);

    void recordFailedAttempt(String role, String reason);

    void updateLeaseInfo(String role, String holderIdentity, long acquireTime, long renewTime,
                         int leaseDurationSeconds, int leaderTransitions);

}
