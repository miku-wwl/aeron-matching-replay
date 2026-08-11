package dev.replaylab.jobdemo.domain;

public enum JobState {
    SCHEDULED,
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
