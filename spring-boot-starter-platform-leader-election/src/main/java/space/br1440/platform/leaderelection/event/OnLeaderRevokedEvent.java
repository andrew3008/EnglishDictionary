package space.br1440.platform.leaderelection.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OnLeaderRevokedEvent extends ApplicationEvent {

    private final String leadershipName;

    public OnLeaderRevokedEvent(Object source, String leadershipName) {
        super(source);
        this.leadershipName = leadershipName;
    }

}
