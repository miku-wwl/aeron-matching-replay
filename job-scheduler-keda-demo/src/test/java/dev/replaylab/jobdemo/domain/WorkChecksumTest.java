package dev.replaylab.jobdemo.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkChecksumTest {

    @Test
    void resumedChecksumMatchesUninterruptedWork() {
        long uninterrupted = 0;
        for (int unit = 1; unit <= 1_000; unit++) {
            uninterrupted = WorkChecksum.mix(uninterrupted, unit);
        }

        long checkpoint = 0;
        for (int unit = 1; unit <= 400; unit++) {
            checkpoint = WorkChecksum.mix(checkpoint, unit);
        }
        long resumed = checkpoint;
        for (int unit = 401; unit <= 1_000; unit++) {
            resumed = WorkChecksum.mix(resumed, unit);
        }

        assertThat(resumed).isEqualTo(uninterrupted);
    }
}
