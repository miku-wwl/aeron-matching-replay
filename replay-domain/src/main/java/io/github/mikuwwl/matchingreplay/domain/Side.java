package io.github.mikuwwl.matchingreplay.domain;

public enum Side
{
    BUY(1),
    SELL(2);

    private final int code;

    Side(final int code)
    {
        this.code = code;
    }

    public int code()
    {
        return code;
    }

    public static Side fromCode(final int code)
    {
        return switch (code)
        {
            case 1 -> BUY;
            case 2 -> SELL;
            default -> throw new IllegalArgumentException("Unknown side code: " + code);
        };
    }
}
