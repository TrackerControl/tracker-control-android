# Design: Timeline-Based Main Activity

## Problem

The current main activity is a flat app list sorted by tracker count. It tells users
"which apps have trackers" but not:

- **What's happening now?** Which apps are actively being tracked?
- **What did blocking break?** Which apps have mixed blocked/allowed connections
  where user adjustment would help?
- **What should I do?** Where does intervention have the most impact?

Users who want to unblock a specific tracker category must remember the affected app,
find it in the list, tap into DetailsActivity, and locate the category. There's no
signal about recency or active blocking impact.

## Proposal: Timeline with Active Blocking Surface

Replace the default main screen with a tracker activity timeline grouped by app and
time. Keep the current app list available as a toggle.

### Layout

```
+--------------------------------------+
| [Insights Hero Card - unchanged]     |
| 847 tracking attempts - 23 companies |
+--------------------------------------+
|                                      |
| ACTIVE BLOCKING              last 1h |  <- section header
|                                      |
| [icon] WhatsApp              3m ago  |  <- mixed: user action useful
|   X  Facebook Analytics, Crashlytics |
|   OK Graph API                       |
|   Tap to manage ->                   |
|                                      |
| [icon] Instagram             8m ago  |  <- fully blocked, working
|   X  4 trackers blocked              |
|                                      |
| TODAY                                |  <- time divider
|                                      |
| [icon] Chrome                2h ago  |
|   X  Google Analytics, DoubleClick   |
|   OK Google APIs                     |
|                                      |
| [icon] Twitter               4h ago  |
|   OK 2 trackers (no blocking)        |
+--------------------------------------+
```

### Sections

1. **Active blocking** (top, always visible when VPN on):
   Apps with blocked connections in the last hour. Mixed entries (some blocked +
   some allowed) are highlighted -- these are where user adjustment matters most.
   Capped at ~10 entries.

2. **Time-bucketed history** (scrollable):
   "Today" / "Yesterday" / "This week". Same per-app grouping, less prominent.

3. **All apps fallback** (menu toggle):
   The current AdapterRule list, for users who prefer it or when VPN is off.

### Data model

```java
class TimelineEntry {
    int uid;
    String appName;
    String packageName;
    long mostRecentTime;
    List<TrackerContact> blocked;   // blocked tracker companies
    List<TrackerContact> allowed;   // allowed tracker companies
    boolean hasMixed;               // both blocked and allowed exist
}

class TrackerContact {
    String companyName;
    String category;
    String hostname;
    long time;
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
2. Filter out non-tracker destinations (this is a tracker timeline, not a log)
3. Group by (uid, time bucket)
4. Partition each group into blocked/allowed lists
5. Sort by `mostRecentTime` DESC

Uses the existing `idx_access` index. The TrackerList lookup is a HashMap -- fast.

### What changes

| Component | Change |
|-----------|--------|
| `TimelineAdapter` | New -- RecyclerView adapter for timeline entries |
| `TimelineEntry` | New -- data model for grouped tracker activity |
| `item_timeline.xml` | New -- timeline row layout |
| `item_timeline_section.xml` | New -- section header layout |
| `DatabaseHelper` | Add `getRecentTrackerActivityGrouped()` method |
| `ActivityMain` | Default to timeline; menu toggle to switch to app list |

### What stays the same

| Component | Reason |
|-----------|--------|
| `InsightsHeaderAdapter` | Hero card at top, works with both views |
| `AdapterRule` | Still used for app-list toggle, and as VPN-off fallback |
| `Rule.java`, `TrackerList.java` | No model changes needed |
| `DetailsActivity` | Navigation target -- unchanged |
| VPN toggle, search, filters | Menu stays, search filters timeline entries |

### Navigation flow

```
Timeline entry (tap)
  -> DetailsActivity for that app
    -> Trackers tab shows categories
      -> User toggles blocking per category
        -> Back to timeline, entry updates on next refresh
```

The "Tap to manage" affordance on mixed entries (some blocked, some allowed)
creates a clear action path. Users see "WhatsApp has 2 blocked trackers" and
can immediately go fix it if WhatsApp is misbehaving.

### Empty / edge states

| State | Behavior |
|-------|----------|
| VPN off | Show app list with prompt "Enable TrackerControl to see tracker activity" |
| VPN on, no data yet | Show app list with message "Monitoring... timeline will appear as apps connect" |
| Search active | Filter timeline entries by app name (same as current) |
| Very old data only | Time sections collapse; "This week" catches everything |

### Risks

1. **Performance** -- access table can be large with heavy users. The grouped query
   and 7-day time filter bound the scan. Can add LIMIT as safety valve.

2. **Visual noise** -- too many entries becomes a log. Active blocking section caps
   at 10; history section should paginate or lazy-load.

3. **Tracker resolution misses** -- some `daddr` values won't map to known trackers.
   Filter these out; the connection log (ActivityLog) already covers raw traffic.

4. **User expectations** -- some users may prefer the app list. The menu toggle
   preserves it. Could also remember the user's preference in SharedPreferences.
