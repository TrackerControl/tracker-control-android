package net.kollnig.missioncontrol.data;

import androidx.annotation.NonNull;

public class StaticTracker implements Comparable<StaticTracker> {
    private final String name;
    private final String web;
    private final int id;
    private final String sign;

    public StaticTracker(@NonNull String name, String web, Integer id, String sign) {
        name = name.replaceAll("[°²?µ]","");
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
        return id == tracker.id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return name;
    }

    public String getName() {
        return name;
    }

    public String getWeb() {
        return web;
    }

    public Integer getId() {
        return id;
    }

    public String getSign() {
        return sign;
    }

    @Override
    public int compareTo(StaticTracker t) {
        return name.compareTo(t.name);
    }
}
