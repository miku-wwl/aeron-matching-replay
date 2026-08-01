package io.github.mikuwwl.matchingreplay.codec;

public class CodecException extends IllegalArgumentException
{
    public CodecException(final String message)
    {
        super(message);
    }

    public CodecException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
