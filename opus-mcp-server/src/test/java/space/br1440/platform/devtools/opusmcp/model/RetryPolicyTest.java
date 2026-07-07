package space.br1440.platform.devtools.opusmcp.model;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private RetryPolicy policy(int maxAttempts) {
        return new RetryPolicy(maxAttempts, 1, 4, millis -> { }, new Random(1));
    }

    @Test
    void retriesTransientThenSucceeds() throws OpusClientException {
        AtomicInteger calls = new AtomicInteger();
        String result = policy(3).execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "boom", 503);
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryNonRetryableStatus() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> policy(5).execute(() -> {
            calls.incrementAndGet();
            throw new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "auth", 401);
        })).isInstanceOf(OpusClientException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void stopsAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> policy(3).execute(() -> {
            calls.incrementAndGet();
            throw new OpusClientException(OpusClientException.Reason.TIMEOUT, "timeout");
        })).isInstanceOf(OpusClientException.class);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void retryableClassification() {
        assertThat(RetryPolicy.isRetryable(
                new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "x", 500))).isTrue();
        assertThat(RetryPolicy.isRetryable(
                new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "x", 400))).isFalse();
        assertThat(RetryPolicy.isRetryable(
                new OpusClientException(OpusClientException.Reason.NETWORK_ERROR, "x"))).isTrue();
        assertThat(RetryPolicy.isRetryable(
                new OpusClientException(OpusClientException.Reason.MODEL_NOT_ALLOWED, "x"))).isFalse();
    }
}
