package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AeronArchiveClientFactory
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AeronArchiveClientFactory.class);

    private final ReplayProperties properties;

    public AeronArchiveClientFactory(final ReplayProperties properties)
    {
        this.properties = properties;
    }

    public Aeron connectAeron(final String clientName)
    {
        return Aeron.connect(
            new Aeron.Context()
                .aeronDirectoryName(properties.getAeronDirectory().toAbsolutePath().normalize().toString())
                .clientName(clientName)
                .errorHandler(ex -> LOGGER.error("Aeron client failure: client={}", clientName, ex)));
    }

    public AeronArchive connectArchive(final Aeron aeron, final String clientName)
    {
        final ReplayProperties.Archive archive = properties.getArchive();
        return AeronArchive.connect(
            new AeronArchive.Context()
                .aeron(aeron)
                .ownsAeronClient(false)
                .clientName(clientName)
                .controlRequestChannel(archive.getControlRequestChannel())
                .controlRequestStreamId(archive.getControlRequestStreamId())
                .controlResponseChannel(archive.getControlResponseChannel())
                .messageTimeoutNs(properties.getTimeout().toNanos())
                .errorHandler(ex -> LOGGER.error("Aeron Archive client failure: client={}", clientName, ex)));
    }
}
