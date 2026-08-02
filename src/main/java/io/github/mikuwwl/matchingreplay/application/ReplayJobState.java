package io.github.mikuwwl.matchingreplay.application;

public enum ReplayJobState
{
    QUEUED,
    RUNNING,
    VERIFIED,
    VERIFICATION_FAILED,
    FAILED
}
