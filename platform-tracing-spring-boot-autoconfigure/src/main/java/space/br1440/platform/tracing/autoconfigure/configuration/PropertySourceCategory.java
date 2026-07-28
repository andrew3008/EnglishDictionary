package space.br1440.platform.tracing.autoconfigure.configuration;

enum PropertySourceCategory {
    CONFIG_SERVER_REMOTE,
    CONFIG_CLIENT_MARKER,
    SYSTEM,
    COMMAND_LINE,
    LOCAL_YAML,
    UNKNOWN_AMBIGUOUS,
    ABSENT
}
