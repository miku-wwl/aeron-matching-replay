package io.github.mikuwwl.matchingreplay.domain;

public enum EventType
{
    ORDER_ACCEPTED(1),
    TRADE_EXECUTED(2),
    ORDER_PARTIALLY_FILLED(3),
    ORDER_FILLED(4);

    private final int code;

    EventType(final int code)
    {
        this.code = code;
    }

    public int code()
    {
        return code;
    }

    public static EventType fromCode(final int code)
    {
        return switch (code)
        {
            case 1 -> ORDER_ACCEPTED;
            case 2 -> TRADE_EXECUTED;
            case 3 -> ORDER_PARTIALLY_FILLED;
            case 4 -> ORDER_FILLED;
            default -> throw new IllegalArgumentException("Unknown event type code: " + code);
        };
    }
}
