package io.github.mikuwwl.matchingreplay.codec;

import io.github.mikuwwl.matchingreplay.codec.generated.MessageHeaderDecoder;
import io.github.mikuwwl.matchingreplay.codec.generated.MessageHeaderEncoder;
import io.github.mikuwwl.matchingreplay.codec.generated.OrderAcceptedEncoder;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
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
            event((short)2, EventType.ORDER_ACCEPTED, 1, 0, 0, 10, 12),
            event((short)2, EventType.TRADE_EXECUTED, 2, 91, 7, 4, 12),
            event((short)2, EventType.ORDER_PARTIALLY_FILLED, 3, 0, 0, 6, 12),
            event((short)2, EventType.ORDER_FILLED, 4, 0, 0, 0, 12));

        for (final MatchingEvent event : events)
        {
            final int length = encoder.encode(event, buffer, 11);
            assertTrue(length <= MatchingEventSbeEncoder.MAX_ENCODED_LENGTH);
            assertEquals(event, dispatcher.decode(buffer, 11, length));
        }
    }

    @Test
    void v2DecoderReplaysV1Recording()
    {
        final MatchingEvent v1Event =
            event((short)1, EventType.ORDER_ACCEPTED, 1, 0, 0, 10, 0);

        final int length = encoder.encode(v1Event, buffer, 0);
        final MatchingEvent decoded = dispatcher.decode(buffer, 0, length);

        assertEquals((short)1, decoded.schemaVersion());
        assertEquals(0, decoded.sourceId());
        assertEquals(v1Event, decoded);
        assertEquals(
            OrderAcceptedEncoder.sourceIdEncodingOffset(),
            new MessageHeaderDecoder().wrap(buffer, 0).blockLength());
    }

    @Test
    void v2DecoderReplaysV2Recording()
    {
        final MatchingEvent v2Event =
            event((short)2, EventType.ORDER_ACCEPTED, 1, 0, 0, 10, 77);

        final int length = encoder.encode(v2Event, buffer, 0);
        final MatchingEvent decoded = dispatcher.decode(buffer, 0, length);

        assertEquals((short)2, decoded.schemaVersion());
        assertEquals(77, decoded.sourceId());
        assertEquals(v2Event, decoded);
    }

    @Test
    void futureSchemaVersionFailsClearly()
    {
        final int length = encoder.encode(
            event((short)2, EventType.ORDER_ACCEPTED, 1, 0, 0, 10, 1),
            buffer,
            0);
        new MessageHeaderEncoder().wrap(buffer, 0).version(3);

        final CodecException exception = assertThrows(
            CodecException.class,
            () -> dispatcher.decode(buffer, 0, length));

        assertEquals(ReplayFailureCode.UNSUPPORTED_SCHEMA, exception.failure().code());
        assertEquals(1, exception.failure().templateId());
        assertEquals(100, exception.failure().schemaId());
        assertEquals(3, exception.failure().actingVersion());
    }

    @Test
    void supportsMaximumLongIntegerFields()
    {
        final MatchingEvent event = new MatchingEvent(
            (short)2,
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
            Long.MAX_VALUE,
            Integer.MAX_VALUE);
        final int length = encoder.encode(event, buffer, 0);
        assertEquals(event, dispatcher.decode(buffer, 0, length));
    }

    @Test
    void failsFastForInvalidSchemaShortMessageAndUnknownTemplate()
    {
        final int length = encoder.encode(
            event((short)2, EventType.ORDER_ACCEPTED, 1, 0, 0, 10, 1),
            buffer,
            0);

        final MessageHeaderEncoder header = new MessageHeaderEncoder().wrap(buffer, 0);
        header.schemaId(999);
        final CodecException wrongSchema = assertThrows(
            CodecException.class,
            () -> dispatcher.decode(buffer, 0, length));
        assertEquals(ReplayFailureCode.UNSUPPORTED_SCHEMA, wrongSchema.failure().code());

        header.schemaId(MatchingEventSbeDispatcher.SCHEMA_ID)
            .version(MatchingEventSbeDispatcher.SCHEMA_VERSION)
            .templateId(999);
        final UnknownTemplateException unknownTemplate = assertThrows(
            UnknownTemplateException.class,
            () -> dispatcher.decode(buffer, 0, length));
        assertEquals(
            ReplayFailureCode.UNSUPPORTED_SCHEMA,
            unknownTemplate.failure().code());

        assertThrows(
            CodecException.class,
            () -> dispatcher.decode(
                buffer,
                0,
                MessageHeaderDecoder.ENCODED_LENGTH - 1));
        assertThrows(
            CodecException.class,
            () -> dispatcher.decode(
                buffer,
                0,
                MessageHeaderDecoder.ENCODED_LENGTH + 1));
    }

    @Test
    void failsFastForUnknownEnumValue()
    {
        final int length = encoder.encode(
            event((short)2, EventType.ORDER_ACCEPTED, 1, 0, 0, 10, 1),
            buffer,
            0);
        final int sideOffset =
            MessageHeaderEncoder.ENCODED_LENGTH +
                OrderAcceptedEncoder.sideEncodingOffset();
        buffer.putByte(sideOffset, (byte)77);
        final CodecException exception = assertThrows(
            CodecException.class,
            () -> dispatcher.decode(buffer, 0, length));
        assertEquals(ReplayFailureCode.SBE_DECODE_FAILED, exception.failure().code());
    }

    @Test
    void generatedMessageHeaderDispatchesTemplates()
    {
        final int length = encoder.encode(
            event((short)2, EventType.TRADE_EXECUTED, 1, 2, 3, 0, 1),
            buffer,
            0);
        final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buffer, 0);
        assertEquals(3, header.templateId());
        assertEquals(100, header.schemaId());
        assertEquals(2, header.version());
        assertEquals(
            length - MessageHeaderDecoder.ENCODED_LENGTH,
            header.blockLength());
    }

    private static MatchingEvent event(
        final short schemaVersion,
        final EventType type,
        final long sequence,
        final long contraOrderId,
        final long tradeId,
        final long remaining,
        final int sourceId)
    {
        return new MatchingEvent(
            schemaVersion,
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
            remaining,
            sourceId);
    }
}
