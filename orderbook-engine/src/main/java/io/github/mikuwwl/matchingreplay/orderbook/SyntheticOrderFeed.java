package io.github.mikuwwl.matchingreplay.orderbook;

import io.github.mikuwwl.matchingreplay.domain.LimitOrderCommand;
import io.github.mikuwwl.matchingreplay.domain.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public final class SyntheticOrderFeed
{
    public static final int DEFAULT_ORDER_COUNT = 5_000;
    public static final long DEFAULT_SEED = 20_210_801L;
    public static final int DEFAULT_SYMBOL_ID = 1;

    private SyntheticOrderFeed()
    {
    }

    public static List<LimitOrderCommand> generate(
        final int orderCount,
        final long seed,
        final int symbolId)
    {
        if (orderCount <= 0)
        {
            throw new IllegalArgumentException("orderCount must be positive");
        }

        final SplittableRandom random = new SplittableRandom(seed);
        final List<LimitOrderCommand> commands = new ArrayList<>(orderCount);
        for (int index = 0; index < orderCount; index++)
        {
            final int phase = index & 3;
            final Side side = phase == 0 || phase == 3 ? Side.SELL : Side.BUY;
            final long price = switch (phase)
            {
                case 0 -> 100_000L + random.nextLong(-20, 21);
                case 1 -> 99_900L + random.nextLong(-20, 21);
                case 2 -> 100_100L + random.nextLong(-20, 21);
                default -> 99_800L + random.nextLong(-20, 21);
            };
            final long quantity = random.nextLong(1, 101);
            final long timestamp = 1_000_000_000L + index * 1_000L;
            commands.add(new LimitOrderCommand(index + 1L, symbolId, side, price, quantity, timestamp));
        }
        return List.copyOf(commands);
    }
}
