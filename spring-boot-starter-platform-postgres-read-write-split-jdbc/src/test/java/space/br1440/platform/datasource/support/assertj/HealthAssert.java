package space.br1440.platform.datasource.support.assertj;

import org.assertj.core.api.AbstractAssert;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Fluent AssertJ для Spring Actuator {@link Health}.
 */
public class HealthAssert extends AbstractAssert<HealthAssert, Health> {

    public HealthAssert(Health health) {
        super(health, HealthAssert.class);
    }

    public HealthAssert isUp() {
        return hasStatus(Status.UP);
    }

    public HealthAssert isDown() {
        return hasStatus(Status.DOWN);
    }

    public HealthAssert isDegraded() {
        return hasStatusCode("DEGRADED");
    }

    public HealthAssert hasStatus(Status status) {
        isNotNull();
        if (!actual.getStatus().equals(status)) {
            failWithMessage("Expected status <%s> but was <%s>", status, actual.getStatus());
        }
        return this;
    }

    public HealthAssert hasStatusCode(String code) {
        isNotNull();
        if (!code.equals(actual.getStatus().getCode())) {
            failWithMessage("Expected status code <%s> but was <%s>", code, actual.getStatus().getCode());
        }
        return this;
    }

    public HealthAssert hasDetail(String key, Object expected) {
        isNotNull();
        Object value = actual.getDetails().get(key);
        if (value == null) {
            failWithMessage("Expected detail <%s>=<%s> but key missing. Details: %s",
                    key, expected, actual.getDetails());
        } else if (!value.equals(expected)) {
            failWithMessage("Expected detail <%s>=<%s> but was <%s>", key, expected, value);
        }
        return this;
    }

    public HealthAssert hasDetailKeys(String... keys) {
        isNotNull();
        for (String key : keys) {
            if (!actual.getDetails().containsKey(key)) {
                failWithMessage("Expected detail key <%s> to be present. Details: %s",
                        key, actual.getDetails());
            }
        }
        return this;
    }
}
