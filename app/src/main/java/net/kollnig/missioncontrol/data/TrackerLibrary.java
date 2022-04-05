package net.kollnig.missioncontrol.data;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Stores information about a found tracker library in an app code
 */
public class TrackerLibrary implements Comparable<TrackerLibrary> {
    private final String name;
    private final String web;
    private final int id;
    private final String sign;

    /**
     * Create class to store information about a tracker found in an app
     *
     * @param name The name of the tracker
     * @param web  The website of the tracker
     * @param id   The identifier of the tracker in the Exodus database
     * @param sign The class signature of the tracker
     */
    public TrackerLibrary(@NonNull String name, String web, Integer id, String sign) {
        name = name.replaceAll("[°²?µ]", "").trim();
        this.name = name;
        this.web = web;
        this.id = id;
        this.sign = sign;
    }

    /**
     * Compare two classes with tracker information and check if they are equal
     *
     * @param o StaticTracker to check equality with
     * @return Information if two classes are equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrackerLibrary tracker = (TrackerLibrary) o;
        return Objects.equals(name, tracker.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    /**
     * Get name of tracker
     *
     * @return Name of tracker
     */
    public String getName() {
        return name;
    }

    /**
     * Get Exodus identifier of tracker
     *
     * @return Exodus identifier of tracker
     */
    public Integer getId() {
        return id;
    }

    @Override
    public int compareTo(TrackerLibrary t) {
        return name.compareTo(t.name);
    }
}
