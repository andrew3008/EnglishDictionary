package space.br1440.platform.logging.configuration;


import io.grpc.ServerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import space.br1440.platform.logging.logback.grpc.GrpcLoggingServerInterceptor;


@Configuration
@ConditionalOnClass(ServerInterceptor.class)
public class GrpcLoggingConfiguration {

    @Bean
    @GlobalServerInterceptor
    public GrpcLoggingServerInterceptor grpcLoggingServerInterceptor() {
        return new GrpcLoggingServerInterceptor();
    }
}
