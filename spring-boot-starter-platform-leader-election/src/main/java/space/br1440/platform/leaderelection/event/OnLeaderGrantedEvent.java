package space.br1440.platform.leaderelection.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OnLeaderGrantedEvent extends ApplicationEvent {

    private final String leadershipName;

    public OnLeaderGrantedEvent(Object source, String leadershipName) {
        super(source);
        this.leadershipName = leadershipName;
    }

}
