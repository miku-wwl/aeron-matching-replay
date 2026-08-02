package io.github.mikuwwl.matchingreplay.codec;

import io.github.mikuwwl.matchingreplay.codec.generated.MessageHeaderDecoder;
import io.github.mikuwwl.matchingreplay.codec.generated.OrderAcceptedDecoder;
import io.github.mikuwwl.matchingreplay.codec.generated.OrderMatchState;
import io.github.mikuwwl.matchingreplay.codec.generated.OrderMatchedDecoder;
import io.github.mikuwwl.matchingreplay.codec.generated.SbeSide;
import io.github.mikuwwl.matchingreplay.codec.generated.TradeCreatedDecoder;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import org.agrona.DirectBuffer;

public final class MatchingEventSbeDispatcher
{
    public static final int SCHEMA_ID = 100;
    public static final int MINIMUM_SCHEMA_VERSION = 1;
    public static final int SCHEMA_VERSION = 2;

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final OrderAcceptedDecoder acceptedDecoder = new OrderAcceptedDecoder();
    private final OrderMatchedDecoder matchedDecoder = new OrderMatchedDecoder();
    private final TradeCreatedDecoder tradeDecoder = new TradeCreatedDecoder();

    public MatchingEvent decode(
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH)
        {
            throw new CodecException("Short SBE message header: length=" + length);
        }

        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();
        final int schemaId = headerDecoder.schemaId();
        if (schemaId != SCHEMA_ID)
        {
            throw new CodecException(
                ReplayFailureCode.UNSUPPORTED_SCHEMA,
                "Unsupported SBE schemaId=" + schemaId +
                    ", expected=" + SCHEMA_ID +
                    ", templateId=" + templateId +
                    ", actingVersion=" + headerDecoder.version(),
                templateId,
                schemaId,
                headerDecoder.version());
        }

        final int actingVersion = headerDecoder.version();
        if (actingVersion < MINIMUM_SCHEMA_VERSION || actingVersion > SCHEMA_VERSION)
        {
            throw new CodecException(
                ReplayFailureCode.UNSUPPORTED_SCHEMA,
                "Unsupported SBE actingVersion=" + actingVersion +
                    ", supportedRange=[" + MINIMUM_SCHEMA_VERSION + ", " +
                    SCHEMA_VERSION + "], templateId=" + templateId +
                    ", schemaId=" + schemaId,
                templateId,
                schemaId,
                actingVersion);
        }

        final int actingBlockLength = headerDecoder.blockLength();
        if (length < MessageHeaderDecoder.ENCODED_LENGTH + actingBlockLength)
        {
            throw new CodecException(
                "Short SBE message body: length=" + length + ", actingBlockLength=" + actingBlockLength);
        }

        final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        try
        {
            return switch (headerDecoder.templateId())
            {
                case OrderAcceptedDecoder.TEMPLATE_ID ->
                    decodeAccepted(buffer, bodyOffset, actingBlockLength, actingVersion);
                case OrderMatchedDecoder.TEMPLATE_ID ->
                    decodeMatched(buffer, bodyOffset, actingBlockLength, actingVersion);
                case TradeCreatedDecoder.TEMPLATE_ID ->
                    decodeTrade(buffer, bodyOffset, actingBlockLength, actingVersion);
                default -> throw new UnknownTemplateException(
                    templateId,
                    schemaId,
                    actingVersion);
            };
        }
        catch (final CodecException ex)
        {
            throw ex;
        }
        catch (final RuntimeException ex)
        {
            throw new CodecException(
                ReplayFailureCode.SBE_DECODE_FAILED,
                "Invalid SBE matching event: templateId=" + templateId +
                    ", schemaId=" + schemaId +
                    ", actingVersion=" + actingVersion,
                templateId,
                schemaId,
                actingVersion,
                ex);
        }
    }

    private MatchingEvent decodeAccepted(
        final DirectBuffer buffer,
        final int offset,
        final int actingBlockLength,
        final int actingVersion)
    {
        acceptedDecoder.wrap(buffer, offset, actingBlockLength, actingVersion);
        return new MatchingEvent(
            (short)actingVersion,
            EventType.ORDER_ACCEPTED,
            acceptedDecoder.eventSequence(),
            acceptedDecoder.timestampNs(),
            acceptedDecoder.orderId(),
            0,
            0,
            acceptedDecoder.symbolId(),
            toDomain(acceptedDecoder.side()),
            acceptedDecoder.price(),
            acceptedDecoder.quantity(),
            acceptedDecoder.remainingQuantity(),
            acceptedDecoder.sourceId());
    }

    private MatchingEvent decodeMatched(
        final DirectBuffer buffer,
        final int offset,
        final int actingBlockLength,
        final int actingVersion)
    {
        matchedDecoder.wrap(buffer, offset, actingBlockLength, actingVersion);
        final EventType eventType = switch (matchedDecoder.state())
        {
            case PARTIALLY_FILLED -> EventType.ORDER_PARTIALLY_FILLED;
            case FILLED -> EventType.ORDER_FILLED;
            default -> throw new CodecException("Invalid order match state");
        };
        return new MatchingEvent(
            (short)actingVersion,
            eventType,
            matchedDecoder.eventSequence(),
            matchedDecoder.timestampNs(),
            matchedDecoder.orderId(),
            0,
            0,
            matchedDecoder.symbolId(),
            toDomain(matchedDecoder.side()),
            matchedDecoder.price(),
            matchedDecoder.quantity(),
            matchedDecoder.remainingQuantity(),
            matchedDecoder.sourceId());
    }

    private MatchingEvent decodeTrade(
        final DirectBuffer buffer,
        final int offset,
        final int actingBlockLength,
        final int actingVersion)
    {
        tradeDecoder.wrap(buffer, offset, actingBlockLength, actingVersion);
        return new MatchingEvent(
            (short)actingVersion,
            EventType.TRADE_EXECUTED,
            tradeDecoder.eventSequence(),
            tradeDecoder.timestampNs(),
            tradeDecoder.takerOrderId(),
            tradeDecoder.makerOrderId(),
            tradeDecoder.tradeId(),
            tradeDecoder.symbolId(),
            toDomain(tradeDecoder.takerSide()),
            tradeDecoder.price(),
            tradeDecoder.quantity(),
            tradeDecoder.takerRemainingQuantity(),
            tradeDecoder.sourceId());
    }

    private static Side toDomain(final SbeSide side)
    {
        return switch (side)
        {
            case BUY -> Side.BUY;
            case SELL -> Side.SELL;
            default -> throw new CodecException("Invalid SBE side");
        };
    }
}
