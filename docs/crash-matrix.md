# Crash Matrix

| Crash Point | Archive | Consumer Checkpoint | Recovery action | Expected result |
|---|---|---|---|---|
| Before Publication | No new event | Old position | No event to recover | Upstream command replay is required and outside this MVP |
| `offer` accepted, Archive not caught up | May not yet be durable | Old position | Do not claim durability | Engine waits for recording position before successful completion |
| Archive recorded, consumer has not received | Event present | Old position | Replay | Recover event |
| Consumer received, before apply | Event present | Old position | Replay | Apply event |
| Consumer applied and checkpointed | Event present | New end position | Replay from new position | Continue with next event |
| Consumer applied, before checkpoint replacement | Event present | Old position | Replay may redeliver | Business sequence suppresses duplicate effect |
| Replay crashes | Recording remains | Latest replay checkpoint | Start bounded replay again | Continue recovery |
| Archive node crashes | Already-recorded files remain | Unchanged | Restart Archive, then replay | Recover the recorded portion |

The demo distinguishes transport acceptance, Archive recording, consumer
application, and consumer checkpoint replacement. They are not equivalent
durability milestones.

> This MVP does not solve “the in-memory matching OrderBook changed but its
> event was not yet accepted by the Aeron Publication.” That requires a command
> log, Aeron Cluster, a synchronous journal, or a more complete persisted
> matching state machine.
