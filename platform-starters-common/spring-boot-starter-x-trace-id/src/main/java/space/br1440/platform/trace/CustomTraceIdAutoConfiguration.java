package space.br1440.platform.trace;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnClass(name = {
        "io.micrometer.tracing.Tracer",
        "jakarta.servlet.Filter",
        "org.springframework.web.filter.OncePerRequestFilter"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(Tracer.class)
public class CustomTraceIdAutoConfiguration {

    @Bean
    public CustomTraceIdFilter customTraceIdFilter() {
        return new CustomTraceIdFilter();
    }

    @Bean
    public TraceIdResponseFilter traceIdResponseFilter(Tracer tracer) {
        return new TraceIdResponseFilter(tracer);
    }
}