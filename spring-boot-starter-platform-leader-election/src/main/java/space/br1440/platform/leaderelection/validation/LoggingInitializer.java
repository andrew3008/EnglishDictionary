package space.br1440.platform.leaderelection.validation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class LoggingInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(@NotNull ConfigurableApplicationContext applicationContext) {
        suppressLeaderElectionWarnings();
    }

    private void suppressLeaderElectionWarnings() {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.addTurboFilter(new LeaderElectionWarningFilter());
        } catch (Throwable ignored) {
        }
    }

    private static class LeaderElectionWarningFilter extends TurboFilter {

        private static final String[] SUPPRESS_PATTERNS = {
                "space.br1440.platform.leaderelection",
                "space.br1440.platform.leaderelection.metrics",
                "space.br1440.platform.leaderelection.lock.persistence.redis",
                "space.br1440.platform.leaderelection.execution.keepalive",
                "space.br1440.platform.leaderelection.schedulerlocking",
                "org.springframework.boot.autoconfigure.data.redis",
                "org.springframework.boot.autoconfigure.ssl",
                "org.springframework.boot.ssl",
                "org.springframework.boot.actuate.autoconfigure.metrics",
                "org.springframework.data.redis",
                "io.lettuce.core",
                "io.micrometer",
                "io.prometheus"
        };
        private static final String BEAN_POST_PROCESSOR_CHECKER = "BeanPostProcessorChecker";

        @Override
        public FilterReply decide(Marker marker, Logger logger, Level level,
                                  String format, Object[] params, Throwable t) {
            if (level == Level.WARN && logger.getName().contains(BEAN_POST_PROCESSOR_CHECKER)) {
                if (format != null) {
                    for (String pattern : SUPPRESS_PATTERNS) {
                        if (format.contains(pattern)) {
                            return FilterReply.DENY;
                        }
                    }
                }
            }
            return FilterReply.NEUTRAL;
        }
    }

}
