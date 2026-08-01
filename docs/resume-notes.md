# Resume Notes

The MVP is organized so each layer can be changed independently:

- update the event schema only in `matching-events.xml`, regenerate, then adapt
  the thin codec dispatcher;
- keep all order-book mutation on its construction/owner thread;
- keep the manifest as the authority for `recordingId`; never select an
  arbitrary latest recording;
- checkpoint the post-fragment `Header.position()` only after the projection
  mutation succeeds;
- always wait for Archive recording position before declaring an engine run
  complete;
- preserve bounded replay semantics until `ReplayMerge` is deliberately added.

The next useful phase is Aeron Archive `ReplayMerge`: replay history, catch the
active recording, then merge to the live stream without a gap.
