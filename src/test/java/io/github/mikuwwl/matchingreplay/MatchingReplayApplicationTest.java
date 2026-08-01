package io.github.mikuwwl.matchingreplay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "matching-replay.aeron-directory=target/test-aeron",
    "matching-replay.checkpoint-directory=target/test-checkpoints"
})
class MatchingReplayApplicationTest
{
    @Test
    void contextLoads()
    {
    }
}
