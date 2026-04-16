package space.br1440.platform.hbrouting.context;

public final class RoutingContextHolder {

    private static final ThreadLocal<String> FEATURE_BRANCH = new ThreadLocal<>();

    private RoutingContextHolder() {
    }

    public static void set(String value) {
        FEATURE_BRANCH.set(value);
    }

    public static String get() {
        return FEATURE_BRANCH.get();
    }

    public static void clear() {
        FEATURE_BRANCH.remove();
    }
}