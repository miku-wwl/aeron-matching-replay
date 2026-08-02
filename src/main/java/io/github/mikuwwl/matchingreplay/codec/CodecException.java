package io.github.mikuwwl.matchingreplay.codec;

import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;

public class CodecException extends ReplayException
{
    public CodecException(final String message)
    {
        this(ReplayFailureCode.SBE_DECODE_FAILED, message, null, null, null, null);
    }

    public CodecException(final String message, final Throwable cause)
    {
        this(ReplayFailureCode.SBE_DECODE_FAILED, message, null, null, null, cause);
    }

    public CodecException(
        final ReplayFailureCode code,
        final String message,
        final Integer templateId,
        final Integer schemaId,
        final Integer actingVersion)
    {
        this(code, message, templateId, schemaId, actingVersion, null);
    }

    public CodecException(
        final ReplayFailureCode code,
        final String message,
        final Integer templateId,
        final Integer schemaId,
        final Integer actingVersion,
        final Integer actingBlockLength,
        final Integer minimumSupportedBlockLength)
    {
        super(ReplayFailure.sbe(
            code,
            message,
            templateId,
            schemaId,
            actingVersion,
            actingBlockLength,
            minimumSupportedBlockLength));
    }

    public CodecException(
        final ReplayFailureCode code,
        final String message,
        final Integer templateId,
        final Integer schemaId,
        final Integer actingVersion,
        final Throwable cause)
    {
        super(
            ReplayFailure.sbe(code, message, templateId, schemaId, actingVersion),
            cause);
    }
}
