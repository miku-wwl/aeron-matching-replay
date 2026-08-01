package io.github.mikuwwl.matchingreplay.codec;

public final class UnknownTemplateException extends CodecException
{
    public UnknownTemplateException(final int templateId)
    {
        super("Unknown SBE templateId: " + templateId);
    }
}
