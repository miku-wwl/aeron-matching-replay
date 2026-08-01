# Matching Event Protocol

The protocol source of truth is
`replay-codec/src/main/resources/sbe/matching-events.xml`. Maven invokes SBE
Tool 1.37.1 during `generate-sources`; the generated Java codecs are compiled
from `target/generated-sources/sbe`.

## Header

The standard generated SBE header contains:

| Field | Meaning |
|---|---|
| `blockLength` | Fixed block length for the selected template |
| `templateId` | 1 accepted, 2 matched/status, 3 trade |
| `schemaId` | 100 |
| `version` | 1 |

The dispatcher rejects short headers/bodies, the wrong schema ID, unsupported
versions, unknown template IDs, and unknown enum values.

## Templates

- `OrderAccepted` carries the accepted order, scaled integer price/quantity,
  side, symbol, timestamp, and business sequence.
- `TradeCreated` carries maker/taker IDs, trade ID, resting price, execution
  quantity, taker remaining quantity, symbol, side, timestamp, and sequence.
- `OrderMatched` carries the incoming order's final post-match status
  (`PARTIALLY_FILLED` or `FILLED`) and remaining quantity.

Prices and quantities are `int64` scaled integers. The hot path contains no
floating point, JSON, object serialization, or handwritten field offsets.

## Encoding and decoding

The publisher reuses a direct Agrona `UnsafeBuffer`. Generated encoders write
the header and body. The subscription's generated decoder wraps the inbound
Agrona `DirectBuffer` directly; it is not copied into a byte array.

`FragmentAssembler` sits before the dispatcher so larger future protocol
versions remain correct when Aeron fragments a message.

Schema/template/field IDs are treated as published identifiers and must not be
deleted and reused.
