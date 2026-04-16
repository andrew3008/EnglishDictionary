package space.br1440.platform.hbrouting.http;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import space.br1440.platform.hbrouting.context.MeshHeaders;
import space.br1440.platform.hbrouting.context.RoutingContextHolder;

public class RoutingRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {

        String branch = RoutingContextHolder.get();

        if (branch != null && !branch.isBlank()) {
            request.getHeaders().set(MeshHeaders.FEATURE_BRANCH, branch);
        }

        return execution.execute(request, body);
    }
}