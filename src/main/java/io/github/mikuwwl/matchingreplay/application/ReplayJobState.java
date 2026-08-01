package io.github.mikuwwl.matchingreplay.application;

public enum ReplayJobState
{
    QUEUED,
    RUNNING,
    SUCCEEDED,
    VERIFICATION_FAILED,
    FAILED
}
