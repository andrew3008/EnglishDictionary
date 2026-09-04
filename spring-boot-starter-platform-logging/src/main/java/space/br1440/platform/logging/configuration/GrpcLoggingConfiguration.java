package space.br1440.platform.logging.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.server.GlobalServerInterceptor;

import space.br1440.platform.logging.logback.grpc.GrpcLoggingServerInterceptor;

import io.grpc.ServerInterceptor;

@AutoConfiguration
@ConditionalOnClass(ServerInterceptor.class)
public class GrpcLoggingConfiguration {

    @Bean
    @GlobalServerInterceptor
    public GrpcLoggingServerInterceptor grpcLoggingServerInterceptor() {
        return new GrpcLoggingServerInterceptor();
    }
}
