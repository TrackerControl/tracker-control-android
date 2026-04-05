# Design: Tracker Activity Timeline

## Problem

The current main activity is a flat app list sorted by tracker count. It tells users
"which apps have trackers" but not:

- **What's happening now?** Which apps are actively being tracked?
- **What did blocking break?** Which apps have mixed blocked/allowed connections
  where user adjustment would help?
- **Should I be worried?** Showing unblocked tracker contacts is arguably the most
  important thing — the whole point of the app is to make tracking visible and
  educate users.

## Proposal: Separate Timeline Activity

A new `ActivityTimeline`, reachable from the main activity's menu. The main activity
stays unchanged — this is an optional, additive feature. If the timeline proves
useful, it could replace the main activity later (at which point the main activity
would need a search feature to find any installed app).

### Layout

A single flat list sorted by recency, with time-bucket section headers:

```
+--------------------------------------+
|  [Toolbar: "Tracker Activity"]       |
+--------------------------------------+
|                                      |
|  LAST HOUR                           |  <- section header
|                                      |
|  [icon] WhatsApp             3m ago  |
|    X  Facebook Analytics             |  <- blocked
|    X  Crashlytics                    |  <- blocked
|    OK Graph API                      |  <- allowed (not a tracker? or unblocked)
|                                      |
|  [icon] Instagram            8m ago  |
|    X  Facebook Analytics             |
|    X  Firebase, Adjust               |
|                                      |
|  TODAY                               |  <- section header
|                                      |
|  [icon] Chrome               2h ago  |
|    X  Google Analytics               |
|    X  DoubleClick                    |
|    OK Google APIs                    |
|                                      |
|  [icon] Twitter              4h ago  |
|    OK 3 tracker companies contacted  |  <- no blocking active — scary
|                                      |
|  YESTERDAY                           |
|                                      |
|  [icon] TikTok             18h ago   |
|    OK 7 tracker companies contacted  |  <- very scary
|                                      |
+--------------------------------------+
```

No separate "active blocking" section — it's just the timeline sorted by recency.
Entries where blocking is active have X/OK indicators per company. Entries with
no blocking at all show the total count of tracker companies contacted, which is
deliberately alarming.

### Visual treatment

Each timeline entry shows:

- **App icon + name + relative time** (top line)
- **Tracker companies with blocked/allowed indicator** (body)
  - Blocked: red X or shield icon + company name
  - Allowed: green checkmark + company name
  - If many companies: show first 3, then "+N more"
- **Mixed entries** (some blocked, some allowed): no special treatment needed —
  the per-company indicators make it obvious
- **Fully unblocked entries**: show company count with no shield — "5 tracker
  companies contacted" — this is the scary/educational case

Tapping any entry navigates to DetailsActivity for that app.

### Data model

```java
class TimelineEntry {
    int uid;
    String appName;
    String packageName;
    long mostRecentTime;
    List<TrackerContact> trackers;  // all tracker contacts, each with blocked/allowed
}

class TrackerContact {
    String companyName;
    String category;
    boolean blocked;
    long lastTime;
}
```

### Query

One indexed query with SQL-level grouping:

```sql
SELECT uid, daddr, allowed, MAX(time) as last_time,
       COUNT(*) as attempts, uncertain
FROM access
WHERE time >= ?   -- 7 days ago
GROUP BY uid, daddr, allowed
ORDER BY last_time DESC
```

Then in Java:
1. Resolve each `daddr` to a Tracker via `TrackerList.findTracker()`
2. Filter out non-tracker destinations (this is a tracker timeline, not a connection log)
3. Group by uid — each uid becomes one TimelineEntry
4. Within each entry, deduplicate by company name, track blocked/allowed per company
5. Sort entries by `mostRecentTime` DESC
6. Insert section headers at time-bucket boundaries

Uses the existing `idx_access` index. TrackerList lookup is a HashMap.

### New files

| File | Purpose |
|------|---------|
| `ActivityTimeline.java` | New activity with RecyclerView + toolbar |
| `TimelineAdapter.java` | Adapter handling both entry rows and section headers |
| `TimelineEntry.java` | Data model for grouped tracker activity per app |
| `TrackerContact.java` | Data model for a single tracker company contact |
| `activity_timeline.xml` | Layout: toolbar + recyclerview |
| `item_timeline_entry.xml` | Layout: app row with tracker list |
| `item_timeline_section.xml` | Layout: section header ("Today", "Yesterday") |
| `DatabaseHelper` | Add `getRecentTrackerActivityGrouped()` method |
| `ActivityMain` | Add menu item to open ActivityTimeline |
| `AndroidManifest.xml` | Register ActivityTimeline |

### What stays the same

Everything in the main activity. This is purely additive.

### Navigation

```
ActivityMain menu -> "Tracker Activity"
  -> ActivityTimeline
    -> tap entry -> DetailsActivity (same extras as current)
      -> user adjusts blocking
        -> back to timeline (refreshes on resume)
```

### Empty states

| State | Behavior |
|-------|----------|
| VPN off, no data | Message: "Enable TrackerControl to see which apps track you" |
| VPN on, no tracker data yet | Message: "Monitoring... activity will appear as apps connect" |
| All entries older than 7 days | Message: "No recent tracker activity" |

### Risks

1. **Performance** — access table can be large. The 7-day filter and GROUP BY
   bound the result set. Add LIMIT 500 as safety valve on the raw query.

2. **Visual noise** — cap displayed tracker companies at 3 per entry with
   "+N more" overflow. Paginate the list if > 50 entries.

3. **Tracker resolution misses** — some `daddr` won't map to known trackers.
   Filter these out; the connection log (ActivityLog) covers raw traffic.

### Future: replacing the main activity

If the timeline proves useful, it could become the default main screen. For that
to work, two things are needed:

1. **App search** — users need to find any installed app, not just those with
   recent activity. A search bar that falls through to the full app list
   (current AdapterRule) would cover this.

2. **VPN toggle** — the main activity's MaterialSwitch for enabling/disabling
   the VPN would need to move into the timeline activity's toolbar.

These are not needed for the initial standalone version.
