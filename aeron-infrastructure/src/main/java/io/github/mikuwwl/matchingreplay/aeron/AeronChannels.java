package io.github.mikuwwl.matchingreplay.aeron;

public final class AeronChannels
{
    public static final String LIVE_CHANNEL = "aeron:ipc";
    public static final int LIVE_STREAM_ID = 1001;

    public static final String REPLAY_CHANNEL = "aeron:ipc";
    public static final int REPLAY_STREAM_ID = 1002;

    public static final String ARCHIVE_LOCAL_CONTROL_CHANNEL = "aeron:ipc?term-length=64k";
    public static final int ARCHIVE_LOCAL_CONTROL_STREAM_ID = 10;
    public static final String ARCHIVE_CONTROL_RESPONSE_CHANNEL = "aeron:ipc";
    public static final String ARCHIVE_CONTROL_CHANNEL = "aeron:udp?endpoint=localhost:8010";
    public static final String ARCHIVE_REPLICATION_CHANNEL = "aeron:udp?endpoint=localhost:0";

    public static final String AERON_DIR_PROPERTY = "replay.aeron.dir";
    public static final String ARCHIVE_DIR_PROPERTY = "replay.archive.dir";
    public static final String RUNTIME_DIR_PROPERTY = "replay.runtime.dir";

    private AeronChannels()
    {
    }
}
