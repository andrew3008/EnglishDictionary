package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

public abstract class TracingControlChannelCondition extends SpringBootCondition {

    private final Set<TracingControlChannel> allowedChannels;

    protected TracingControlChannelCondition(TracingControlChannel... allowedChannels) {
        this.allowedChannels = Set.of(allowedChannels);
    }

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();

        boolean enabled = TracingBootstrapSettingsResolver.parseEnabled(environment).orElse(false);
        boolean matches = enabled && TracingBootstrapSettingsResolver.parseControlChannel(environment)
                .filter(allowedChannels::contains)
                .isPresent();

        return matches
                ? ConditionOutcome.match("Tracing control channel is enabled and allowed")
                : ConditionOutcome.noMatch("Tracing control channel is disabled, invalid, or not allowed");
    }

    public static final class OnNone extends TracingControlChannelCondition {

        public OnNone() {
            super(TracingControlChannel.NONE);
        }
    }

    public static final class OnWritable extends TracingControlChannelCondition {

        public OnWritable() {
            super(TracingControlChannel.CONFIG_SERVER, TracingControlChannel.ACTUATOR);
        }
    }

    public static final class OnConfigServer extends TracingControlChannelCondition {

        public OnConfigServer() {
            super(TracingControlChannel.CONFIG_SERVER);
        }
    }

    public static final class OnActuator extends TracingControlChannelCondition {

        public OnActuator() {
            super(TracingControlChannel.ACTUATOR);
        }
    }
}
