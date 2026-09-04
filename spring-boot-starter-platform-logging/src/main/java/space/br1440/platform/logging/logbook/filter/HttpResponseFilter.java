package space.br1440.platform.logging.logbook.filter;

import org.jspecify.annotations.NullMarked;
import org.slf4j.MDC;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.ResponseFilter;
import space.br1440.platform.logging.mdc.MDCLogKey;

@NullMarked
public class HttpResponseFilter implements ResponseFilter {

    @Override
    public HttpResponse filter(HttpResponse response) {
        String startTimeStr = MDC.get(MDCLogKey.START_TIME.value());
        if (startTimeStr != null) {
            long startTime = Long.parseLong(startTimeStr);
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            MDC.put(MDCLogKey.END_TIME.value(), String.valueOf(endTime));
            MDC.put(MDCLogKey.DURATION.value(), String.valueOf(duration));
        }

        MDC.put(MDCLogKey.HTTP_STATUS_CODE.value(), String.valueOf(response.getStatus()));
        return response;
    }

}
