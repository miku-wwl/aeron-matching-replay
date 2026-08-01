# Agent Rules

1. Build one deployable Spring Boot replay service, not a Maven multi-module system.
2. Treat the matching engine, event publisher, Media Driver, and Aeron Archive as upstream infrastructure; embed them only in tests.
3. Use Maven and Java 21.
4. Define the event protocol in SBE XML and use generated SBE codecs.
5. Do not use JSON on the Aeron hot path.
6. Persist and replay by Aeron Position.
7. Maintain a business `eventSequence` for gap and duplicate checks.
8. Never select an ambiguous recording; every replay command must carry a `recordingId`.
9. Never ignore negative Aeron publication results in test fixtures.
10. A checkpoint must be atomically replaced after applying events.
11. Do not claim this is proprietary OKX source code.
12. Keep production code free of embedded matching-engine and Archive process ownership.
13. Verify the real Aeron Archive path with an integration test.
