package io.github.mikuwwl.matchingreplay.aeron;

import org.springframework.stereotype.Component;

@Component
public final class SystemMonotonicClock implements MonotonicClock
{
    @Override
    public long nanoTime()
    {
        return System.nanoTime();
    }
}
