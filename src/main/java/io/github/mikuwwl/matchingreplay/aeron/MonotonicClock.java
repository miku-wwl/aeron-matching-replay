package io.github.mikuwwl.matchingreplay.aeron;

@FunctionalInterface
public interface MonotonicClock
{
    long nanoTime();
}
