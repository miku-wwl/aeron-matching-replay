package io.github.mikuwwl.matchingreplay.projection;

import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Optional example projection for reviewing the plugin lifecycle.
 *
 * <p>This projection is deliberately not enabled by default. Activate the
 * {@code replay-audit} Spring profile to emit one debug record for each newly
 * applied event. It is an observability example, not durable audit storage.</p>
 */
@Component
@Profile("replay-audit")
@Order(100)
public final class ReplayAuditProjection implements ReplayProjection
{
    private static final Logger LOG =
        LoggerFactory.getLogger(ReplayAuditProjection.class);

    @Override
    public void apply(final MatchingEvent event)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug(
                "REPLAY_AUDIT eventSequence={} eventType={} orderId={} " +
                    "symbolId={} quantity={} remainingQuantity={} sourceId={}",
                event.eventSequence(),
                event.eventType(),
                event.orderId(),
                event.symbolId(),
                event.quantity(),
                event.remainingQuantity(),
                event.sourceId());
        }
    }
}
