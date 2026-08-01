package io.github.mikuwwl.matchingreplay.application;

public class ReplayConflictException extends RuntimeException
{
    public ReplayConflictException(final String message)
    {
        super(message);
    }
}
