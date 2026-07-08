package net.kollnig.missioncontrol.wgbridge;

/**
 * Implemented by the Java side to receive bridge log lines. Called from
 * native tunnel threads.
 */
public interface Logger {
    void verbosef(String message);

    void errorf(String message);
}
