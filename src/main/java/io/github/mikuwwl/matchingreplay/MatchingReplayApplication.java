package io.github.mikuwwl.matchingreplay;

import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ReplayProperties.class)
public class MatchingReplayApplication
{
    public static void main(final String[] args)
    {
        SpringApplication.run(MatchingReplayApplication.class, args);
    }
}
