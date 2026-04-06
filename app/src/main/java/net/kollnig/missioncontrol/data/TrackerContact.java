package net.kollnig.missioncontrol.data;

/**
 * A single tracker company contact observed in network traffic.
 * Used by the timeline to show per-company blocked/allowed state.
 */
public class TrackerContact {
    public final String companyName;
    public final String category;
    public final boolean blocked;
    public final long lastTime;

    public TrackerContact(String companyName, String category, boolean blocked, long lastTime) {
        this.companyName = companyName;
        this.category = category;
        this.blocked = blocked;
        this.lastTime = lastTime;
    }
}
