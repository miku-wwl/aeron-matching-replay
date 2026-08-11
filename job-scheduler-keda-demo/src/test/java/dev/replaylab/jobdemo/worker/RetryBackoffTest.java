package dev.replaylab.jobdemo.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryBackoffTest {

    @Test
    void usesCappedExponentialBackoff() {
        assertThat(RetryBackoff.forAttempt(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(RetryBackoff.forAttempt(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(RetryBackoff.forAttempt(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(RetryBackoff.forAttempt(6)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryBackoff.forAttempt(20)).isEqualTo(Duration.ofSeconds(30));
    }
}
