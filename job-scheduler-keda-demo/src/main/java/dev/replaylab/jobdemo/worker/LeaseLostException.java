package dev.replaylab.jobdemo.worker;

public class LeaseLostException extends RuntimeException {

    public LeaseLostException(String message) {
        super(message);
    }
}
