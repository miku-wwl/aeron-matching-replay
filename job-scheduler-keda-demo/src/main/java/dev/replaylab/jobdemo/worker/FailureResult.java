package dev.replaylab.jobdemo.worker;

public record FailureResult(boolean terminal, long retryDelaySeconds) {
}
