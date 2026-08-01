# Agent Rules

1. Do not replace Aeron or Aeron Archive with another broker.
2. Use Maven, not Gradle.
3. Use Java 21.
4. Use an in-memory price-time-priority OrderBook.
5. Define the protocol in SBE XML and use generated SBE codecs; DirectBuffer/UnsafeBuffer are only the underlying buffers.
6. Do not use JSON on the Aeron hot path.
7. Use ExclusivePublication.
8. Persist and replay by Aeron Position.
9. Also maintain a business eventSequence for gap and duplicate checks.
10. Do not claim this is proprietary OKX source code.
11. Do not add Spring Boot, Kafka, databases, Docker, or Aeron Cluster to the MVP.
12. Run tests after every phase.
13. Keep the repository compiling at every commit.
14. Never ignore negative Publication.offer results.
15. Never select an ambiguous recording silently.
16. The final demo must prove crash recovery with matching final state hash.
