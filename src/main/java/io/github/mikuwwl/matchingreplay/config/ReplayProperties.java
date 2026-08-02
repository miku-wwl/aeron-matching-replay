package io.github.mikuwwl.matchingreplay.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "matching-replay")
public class ReplayProperties
{
    @NotNull
    private Path aeronDirectory;

    @NotNull
    private Path checkpointDirectory = Path.of("./runtime/checkpoints");

    @NotBlank
    private String replayChannel = "aeron:ipc";

    @Min(1)
    private int replayStreamId = 1002;

    @NotNull
    private Duration noProgressTimeout = Duration.ofSeconds(20);

    @NotNull
    private Duration archiveRequestTimeout = Duration.ofSeconds(20);

    private Duration maximumReplayDuration;

    @Min(1)
    private int fragmentLimit = 20;

    @Min(1)
    private int checkpointEveryProcessedMessages = 100;

    @Min(1)
    private int workerCount = 1;

    @Min(0)
    private int queueCapacity = 100;

    @Valid
    @NotNull
    private Archive archive = new Archive();

    public Path getAeronDirectory()
    {
        return aeronDirectory;
    }

    public void setAeronDirectory(final Path aeronDirectory)
    {
        this.aeronDirectory = aeronDirectory;
    }

    public Path getCheckpointDirectory()
    {
        return checkpointDirectory;
    }

    public void setCheckpointDirectory(final Path checkpointDirectory)
    {
        this.checkpointDirectory = checkpointDirectory;
    }

    public String getReplayChannel()
    {
        return replayChannel;
    }

    public void setReplayChannel(final String replayChannel)
    {
        this.replayChannel = replayChannel;
    }

    public int getReplayStreamId()
    {
        return replayStreamId;
    }

    public void setReplayStreamId(final int replayStreamId)
    {
        this.replayStreamId = replayStreamId;
    }

    public Duration getNoProgressTimeout()
    {
        return noProgressTimeout;
    }

    public void setNoProgressTimeout(final Duration noProgressTimeout)
    {
        this.noProgressTimeout = noProgressTimeout;
    }

    public Duration getMaximumReplayDuration()
    {
        return maximumReplayDuration;
    }

    public void setMaximumReplayDuration(final Duration maximumReplayDuration)
    {
        this.maximumReplayDuration = maximumReplayDuration;
    }

    public Duration getArchiveRequestTimeout()
    {
        return archiveRequestTimeout;
    }

    public void setArchiveRequestTimeout(final Duration archiveRequestTimeout)
    {
        this.archiveRequestTimeout = archiveRequestTimeout;
    }

    public int getFragmentLimit()
    {
        return fragmentLimit;
    }

    public void setFragmentLimit(final int fragmentLimit)
    {
        this.fragmentLimit = fragmentLimit;
    }

    public int getCheckpointEveryProcessedMessages()
    {
        return checkpointEveryProcessedMessages;
    }

    public void setCheckpointEveryProcessedMessages(
        final int checkpointEveryProcessedMessages)
    {
        this.checkpointEveryProcessedMessages = checkpointEveryProcessedMessages;
    }

    public int getWorkerCount()
    {
        return workerCount;
    }

    public void setWorkerCount(final int workerCount)
    {
        this.workerCount = workerCount;
    }

    public int getQueueCapacity()
    {
        return queueCapacity;
    }

    public void setQueueCapacity(final int queueCapacity)
    {
        this.queueCapacity = queueCapacity;
    }

    public Archive getArchive()
    {
        return archive;
    }

    public void setArchive(final Archive archive)
    {
        this.archive = archive;
    }

    public static class Archive
    {
        @NotBlank
        private String controlRequestChannel = "aeron:ipc?term-length=64k";

        @Min(1)
        private int controlRequestStreamId = 10;

        @NotBlank
        private String controlResponseChannel = "aeron:ipc";

        public String getControlRequestChannel()
        {
            return controlRequestChannel;
        }

        public void setControlRequestChannel(final String controlRequestChannel)
        {
            this.controlRequestChannel = controlRequestChannel;
        }

        public int getControlRequestStreamId()
        {
            return controlRequestStreamId;
        }

        public void setControlRequestStreamId(final int controlRequestStreamId)
        {
            this.controlRequestStreamId = controlRequestStreamId;
        }

        public String getControlResponseChannel()
        {
            return controlResponseChannel;
        }

        public void setControlResponseChannel(final String controlResponseChannel)
        {
            this.controlResponseChannel = controlResponseChannel;
        }
    }
}
