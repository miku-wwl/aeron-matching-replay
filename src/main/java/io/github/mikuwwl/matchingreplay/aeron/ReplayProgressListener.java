package io.github.mikuwwl.matchingreplay.aeron;

@FunctionalInterface
public interface ReplayProgressListener
{
    void onProgress(ReplayProgress progress);

    static ReplayProgressListener none()
    {
        return ignored -> { };
    }
}
