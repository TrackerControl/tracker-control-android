package net.kollnig.missioncontrol.data;

import androidx.annotation.NonNull;

import java.util.Objects;

public class StaticTracker implements Comparable<StaticTracker> {
    private final String name;
    private final String web;
    private final int id;
    private final String sign;

    public StaticTracker(@NonNull String name, String web, Integer id, String sign) {
        name = name.replaceAll("[°²?µ]","").trim();
        this.name = name;
        this.web = web;
        this.id = id;
        this.sign = sign;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StaticTracker tracker = (StaticTracker) o;
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

    public String getName() {
        return name;
    }

    public Integer getId() {
        return id;
    }

    @Override
    public int compareTo(StaticTracker t) {
        return name.compareTo(t.name);
    }
}
