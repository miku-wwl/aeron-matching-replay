package dev.replaylab.jobdemo.domain;

public final class WorkChecksum {

    private WorkChecksum() {
    }

    public static long mix(long checksum, int unitNumber) {
        long value = checksum ^ (0x9E3779B97F4A7C15L * unitNumber);
        value = Long.rotateLeft(value, 13);
        return value * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L;
    }
}
