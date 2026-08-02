package io.github.mikuwwl.matchingreplay.codec;

import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;

public final class UnknownTemplateException extends CodecException
{
    public UnknownTemplateException(
        final int templateId,
        final int schemaId,
        final int actingVersion)
    {
        super(
            ReplayFailureCode.UNSUPPORTED_SCHEMA,
            "Unknown SBE templateId=" + templateId +
                ", schemaId=" + schemaId +
                ", actingVersion=" + actingVersion,
            templateId,
            schemaId,
            actingVersion);
    }
}
