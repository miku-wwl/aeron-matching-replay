package io.github.mikuwwl.matchingreplay.application;

public class ReplayCapacityException extends RuntimeException
{
    public ReplayCapacityException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
