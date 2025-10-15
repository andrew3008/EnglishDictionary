package space.br1440.platform.metrics.endpoint;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;

@WebEndpoint(id = "platform")
public class BuildInfoEndpoint {

    private BuildInfo buildInfo;

    @ReadOperation
    public synchronized BuildInfo getBuildInfo() {
        if (buildInfo != null) {
            return buildInfo;
        }

        Resource resource = new ClassPathResource("build-info.json");

        if (!resource.exists() || !resource.isReadable()) {
            throw new RuntimeException("Resource 'build-info.json' not found or not readable.");
        }
        try (InputStream inputStream = resource.getInputStream()) {
            ObjectMapper objectMapper = new ObjectMapper();
            buildInfo = objectMapper.readValue(inputStream, BuildInfo.class);
            return buildInfo;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
