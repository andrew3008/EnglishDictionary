package space.br1440.platform.leaderelection.leaderelection.model;

public interface LeaderInfo {

    boolean isLeader();

    String getCurrentLeader();

    String getLeadershipName();

}
