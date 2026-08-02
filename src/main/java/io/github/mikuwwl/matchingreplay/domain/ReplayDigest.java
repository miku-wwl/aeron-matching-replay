package io.github.mikuwwl.matchingreplay.domain;

/**
 * Resumable rolling FNV-1a digest over the canonical replay event fields.
 *
 * <p>The canonical order is eventSequence, eventType, orderId,
 * contraOrderId, tradeId, symbolId, side, price, quantity, and
 * remainingQuantity. Transport metadata, timestamp, schema version, and
 * sourceId are deliberately excluded.</p>
 */
public final class ReplayDigest
{
    public static final long INITIAL_VALUE = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private ReplayDigest()
    {
    }

    public static long mix(final long digest, final long value)
    {
        long result = digest;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE)
        {
            result ^= (value >>> shift) & 0xffL;
            result *= FNV_PRIME;
        }
        return result;
    }

    public static long mixEvent(final long digest, final MatchingEvent event)
    {
        long result = mix(digest, event.eventSequence());
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
