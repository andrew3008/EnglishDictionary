package space.br1440.platform.logging.logback.filter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import space.br1440.platform.logging.SpringContext;
import space.br1440.platform.logging.UserService;
import space.br1440.platform.logging.mdc.MDCLogKey;

public class CommonMdcFilter extends Filter<ILoggingEvent> {

    private static final long PID = ProcessHandle.current().pid();

    @Override
    public FilterReply decide(ILoggingEvent event) {
        try {

            //PID
            MDC.put(MDCLogKey.PID.value(), String.valueOf(PID));
            //USERNAME
            UserService userService = SpringContext.getBean(UserService.class);
            if (userService != null && StringUtils.hasText(userService.getCurrentUsername())) {
                MDC.put(MDCLogKey.USERNAME.value(), userService.getCurrentUsername());
            }

            if(event.getThrowableProxy() != null) {

            }
        } catch (Exception e) {
            //can't add MDC fields
            e.printStackTrace();
        }

        return FilterReply.ACCEPT;
    }
}
