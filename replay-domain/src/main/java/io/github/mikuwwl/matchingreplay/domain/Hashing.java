package io.github.mikuwwl.matchingreplay.domain;

public final class Hashing
{
    public static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Hashing()
    {
    }

    public static long mix(final long hash, final long value)
    {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE)
        {
            result ^= (value >>> shift) & 0xffL;
            result *= FNV_PRIME;
        }
        return result;
    }

    public static long mixEvent(final long hash, final MatchingEvent event)
    {
        long result = mix(hash, event.eventSequence());
        result = mix(result, event.eventType().code());
        result = mix(result, event.orderId());
        result = mix(result, event.contraOrderId());
        result = mix(result, event.tradeId());
        result = mix(result, event.symbolId());
        result = mix(result, event.side().code());
        result = mix(result, event.price());
        result = mix(result, event.quantity());
        return mix(result, event.remainingQuantity());
    }
}
