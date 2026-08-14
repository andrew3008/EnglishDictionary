package space.br1440.platform.tracing.otel.javaagent.resource;

import java.io.InputStream;

@FunctionalInterface
public interface ClasspathResourceLoader {

    InputStream open(String path);

    static ClasspathResourceLoader tcclFirst() {
        return path -> {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl != null) {
                InputStream fromTccl = tccl.getResourceAsStream(path);
                if (fromTccl != null) {
                    return fromTccl;
                }
            }

            return ClasspathResourceLoader.class.getClassLoader().getResourceAsStream(path);
        };
    }
}
