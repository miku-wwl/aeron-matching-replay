package io.github.mikuwwl.matchingreplay.projection;

import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;

/**
 * Extension point for projections that consume an already sequence-validated
 * and newly applied replay event.
 *
 * <p>The core {@link ProjectionState} remains responsible for replay progress,
 * digest, duplicate handling, and checkpoint state. Implementations of this
 * interface are optional and are invoked in registration order. An external
 * implementation must provide its own transactional and idempotency strategy
 * if it creates durable side effects.</p>
 */
@FunctionalInterface
public interface ReplayProjection
{
    void apply(MatchingEvent event);
}
