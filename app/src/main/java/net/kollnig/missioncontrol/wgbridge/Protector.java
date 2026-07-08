package net.kollnig.missioncontrol.wgbridge;

/**
 * Implemented by the Java side. Returns true if VpnService.protect(fd)
 * succeeded. Called from native tunnel threads.
 */
public interface Protector {
    boolean protect(int fd);
}
