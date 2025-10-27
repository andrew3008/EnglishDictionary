package space.br1440.platform.leaderelection.event;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

@Slf4j
@AllArgsConstructor
public class LeadershipEventPublisher {

    private final Object source;
    private final String leadershipName;
    private final ApplicationEventPublisher eventPublisher;

    public void publishOnGranted() {
        eventPublisher.publishEvent(new OnLeaderGrantedEvent(source, leadershipName));
    }

    public void publishOnRevoked() {
        eventPublisher.publishEvent(new OnLeaderRevokedEvent(source, leadershipName));
    }

}
