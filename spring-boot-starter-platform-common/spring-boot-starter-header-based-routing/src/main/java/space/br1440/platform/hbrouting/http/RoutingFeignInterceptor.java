package space.br1440.platform.hbrouting.http;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import space.br1440.platform.hbrouting.context.MeshHeaders;
import space.br1440.platform.hbrouting.context.RoutingContextHolder;

public class RoutingFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String branch = RoutingContextHolder.get();

        if (branch != null && !branch.isBlank()) {
            template.header(MeshHeaders.FEATURE_BRANCH, branch);
        }
    }
}