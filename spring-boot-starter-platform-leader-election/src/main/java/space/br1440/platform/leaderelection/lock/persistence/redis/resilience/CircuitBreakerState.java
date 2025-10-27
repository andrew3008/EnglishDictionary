package space.br1440.platform.leaderelection.lock.persistence.redis.resilience;

public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
