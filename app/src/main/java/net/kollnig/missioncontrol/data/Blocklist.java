package net.kollnig.missioncontrol.data;

import java.io.Serializable;
import java.util.UUID;

public class Blocklist implements Serializable {
    public String uuid;
    public String url;
    public boolean enabled;
    public long lastModified;
    public boolean lastDownloadSuccess;
    public String lastErrorMessage;

    public Blocklist() {
        this.uuid = UUID.randomUUID().toString();
        this.enabled = true;
        this.lastDownloadSuccess = true;
    }

    public Blocklist(String url, boolean enabled) {
        this();
        this.url = url;
        this.enabled = enabled;
    }
}
