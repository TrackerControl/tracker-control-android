package net.kollnig.missioncontrol.data;

import java.util.List;

/**
 * Represents a tracker as defined in the Exodus Privacy database.
 */
public class ExodusTracker {
    public int id;

    public String name;

    public String website;

    public String codeSignature;

    public String networkSignature;

    public List<String> categories;

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getWebsite() {
        return website;
    }

    public String getCodeSignature() {
        return codeSignature;
    }

    public String getNetworkSignature() {
        return networkSignature;
    }

    public List<String> getCategories() {
        return categories;
    }
}
