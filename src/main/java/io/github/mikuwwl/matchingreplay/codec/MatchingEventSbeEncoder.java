package io.github.mikuwwl.matchingreplay.codec;

import io.github.mikuwwl.matchingreplay.codec.generated.MessageHeaderEncoder;
import io.github.mikuwwl.matchingreplay.codec.generated.OrderAcceptedEncoder;
import io.github.mikuwwl.matchingreplay.codec.generated.OrderMatchState;
import io.github.mikuwwl.matchingreplay.codec.generated.OrderMatchedEncoder;
import io.github.mikuwwl.matchingreplay.codec.generated.SbeSide;
import io.github.mikuwwl.matchingreplay.codec.generated.TradeCreatedEncoder;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import org.agrona.MutableDirectBuffer;

public final class MatchingEventSbeEncoder
{
    public static final int MAX_ENCODED_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + TradeCreatedEncoder.BLOCK_LENGTH;

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final OrderAcceptedEncoder acceptedEncoder = new OrderAcceptedEncoder();
    private final OrderMatchedEncoder matchedEncoder = new OrderMatchedEncoder();
    private final TradeCreatedEncoder tradeEncoder = new TradeCreatedEncoder();

    public int encode(final MatchingEvent event, final MutableDirectBuffer buffer, final int offset)
    {
        return switch (event.eventType())
        {
            case ORDER_ACCEPTED -> encodeAccepted(event, buffer, offset);
            case TRADE_EXECUTED -> encodeTrade(event, buffer, offset);
            case ORDER_PARTIALLY_FILLED, ORDER_FILLED -> encodeMatched(event, buffer, offset);
        };
    }

    private int encodeAccepted(
        final MatchingEvent event,
        final MutableDirectBuffer buffer,
        final int offset)
    {
        acceptedEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .eventSequence(event.eventSequence())
            .timestampNs(event.timestampNs())
            .orderId(event.orderId())
            .price(event.price())
            .quantity(event.quantity())
            .remainingQuantity(event.remainingQuantity())
            .symbolId(event.symbolId())
            .side(toSbe(event.side()));
        return MessageHeaderEncoder.ENCODED_LENGTH + acceptedEncoder.encodedLength();
    }

    private int encodeTrade(
        final MatchingEvent event,
        final MutableDirectBuffer buffer,
        final int offset)
    {
        tradeEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .eventSequence(event.eventSequence())
            .timestampNs(event.timestampNs())
            .tradeId(event.tradeId())
            .takerOrderId(event.orderId())
            .makerOrderId(event.contraOrderId())
            .price(event.price())
            .quantity(event.quantity())
            .takerRemainingQuantity(event.remainingQuantity())
            .symbolId(event.symbolId())
            .takerSide(toSbe(event.side()));
        return MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength();
    }

    private int encodeMatched(
        final MatchingEvent event,
        final MutableDirectBuffer buffer,
        final int offset)
    {
        final OrderMatchState state = event.eventType() == EventType.ORDER_FILLED ?
            OrderMatchState.FILLED : OrderMatchState.PARTIALLY_FILLED;
        matchedEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .eventSequence(event.eventSequence())
            .timestampNs(event.timestampNs())
            .orderId(event.orderId())
            .price(event.price())
            .quantity(event.quantity())
            .remainingQuantity(event.remainingQuantity())
            .symbolId(event.symbolId())
            .side(toSbe(event.side()))
            .state(state);
        return MessageHeaderEncoder.ENCODED_LENGTH + matchedEncoder.encodedLength();
    }

    private static SbeSide toSbe(final Side side)
    {
        return side == Side.BUY ? SbeSide.BUY : SbeSide.SELL;
    }
}
