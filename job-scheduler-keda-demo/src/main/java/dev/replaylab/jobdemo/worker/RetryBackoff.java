package dev.replaylab.jobdemo.worker;

import java.time.Duration;

public final class RetryBackoff {

    private RetryBackoff() {
    }

    public static Duration forAttempt(int attemptNumber) {
        int exponent = Math.max(0, Math.min(attemptNumber - 1, 5));
        return Duration.ofSeconds(Math.min(30, 1L << exponent));
    }
}
