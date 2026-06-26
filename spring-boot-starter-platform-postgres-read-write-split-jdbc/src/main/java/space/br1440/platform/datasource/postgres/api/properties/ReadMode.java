package space.br1440.platform.datasource.postgres.api.properties;

/**
 * Семантика read-стратегии, отражающая фактическое поведение pgJDBC {@code targetServerType}.
 */
public enum ReadMode {

    /**
     * targetServerType=preferSecondary — предпочесть реплику, fallback на primary.
     */
    SECONDARY_PREFERRED,

    /**
     * targetServerType=secondary — только реплика, без fallback.
     */
    STRICT_SECONDARY,

    /**
     * targetServerType=any + loadBalanceHosts=true — любая нода, включая primary.
     */
    ANY_NODE_LOAD_BALANCED

}
