package io.github.mikuwwl.matchingreplay.codec;

import io.github.mikuwwl.matchingreplay.codec.generated.MessageHeaderDecoder;
import io.github.mikuwwl.matchingreplay.codec.generated.MessageHeaderEncoder;
import io.github.mikuwwl.matchingreplay.codec.generated.OrderAcceptedEncoder;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingEventSbeCodecTest
{
    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
    private final MatchingEventSbeEncoder encoder = new MatchingEventSbeEncoder();
    private final MatchingEventSbeDispatcher dispatcher = new MatchingEventSbeDispatcher();

    @Test
    void roundTripsAllTemplatesUsingGeneratedCodecs()
    {
        final List<MatchingEvent> events = List.of(
            event(EventType.ORDER_ACCEPTED, 1, 0, 0, 10),
            event(EventType.TRADE_EXECUTED, 2, 91, 7, 4),
            event(EventType.ORDER_PARTIALLY_FILLED, 3, 0, 0, 6),
            event(EventType.ORDER_FILLED, 4, 0, 0, 0));

        for (final MatchingEvent event : events)
        {
            final int length = encoder.encode(event, buffer, 11);
            assertTrue(length <= MatchingEventSbeEncoder.MAX_ENCODED_LENGTH);
            assertEquals(event, dispatcher.decode(buffer, 11, length));
        }
    }

    @Test
    void supportsMaximumLongIntegerFields()
    {
        final MatchingEvent event = new MatchingEvent(
            (short)1,
            EventType.TRADE_EXECUTED,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            Side.SELL,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE);
        final int length = encoder.encode(event, buffer, 0);
        assertEquals(event, dispatcher.decode(buffer, 0, length));
    }

    @Test
    void failsFastForInvalidSchemaVersionShortMessageAndUnknownTemplate()
    {
        final int length = encoder.encode(event(EventType.ORDER_ACCEPTED, 1, 0, 0, 10), buffer, 0);

        final MessageHeaderEncoder header = new MessageHeaderEncoder().wrap(buffer, 0);
        header.schemaId(999);
        assertThrows(CodecException.class, () -> dispatcher.decode(buffer, 0, length));

        header.schemaId(MatchingEventSbeDispatcher.SCHEMA_ID).version(2);
        assertThrows(CodecException.class, () -> dispatcher.decode(buffer, 0, length));

        header.version(MatchingEventSbeDispatcher.SCHEMA_VERSION).templateId(999);
        assertThrows(UnknownTemplateException.class, () -> dispatcher.decode(buffer, 0, length));
        assertThrows(CodecException.class, () ->
            dispatcher.decode(buffer, 0, MessageHeaderDecoder.ENCODED_LENGTH - 1));
        assertThrows(CodecException.class, () ->
            dispatcher.decode(buffer, 0, MessageHeaderDecoder.ENCODED_LENGTH + 1));
    }

    @Test
    void failsFastForUnknownEnumValue()
    {
        final int length = encoder.encode(event(EventType.ORDER_ACCEPTED, 1, 0, 0, 10), buffer, 0);
        final int sideOffset = MessageHeaderEncoder.ENCODED_LENGTH + OrderAcceptedEncoder.sideEncodingOffset();
        buffer.putByte(sideOffset, (byte)77);
        assertThrows(CodecException.class, () -> dispatcher.decode(buffer, 0, length));
    }

    @Test
    void generatedMessageHeaderDispatchesTemplates()
    {
        final int length = encoder.encode(event(EventType.TRADE_EXECUTED, 1, 2, 3, 0), buffer, 0);
        final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buffer, 0);
        assertEquals(3, header.templateId());
        assertEquals(100, header.schemaId());
        assertEquals(1, header.version());
        assertEquals(length - MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength());
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.LITTLE_ENDIAN);
    }

    private static MatchingEvent event(
        final EventType type,
        final long sequence,
        final long contraOrderId,
        final long tradeId,
        final long remaining)
    {
        return new MatchingEvent(
            (short)1,
            type,
            sequence,
            123_000 + sequence,
            10 + sequence,
            contraOrderId,
            tradeId,
            1,
            Side.BUY,
            100_001,
            10,
            remaining);
    }
}
