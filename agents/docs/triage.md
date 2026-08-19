# Reviewing & triaging issues

The standing constraints in AGENTS.md decide most triage. Trace any bug into the
actual code before assigning a verdict — line numbers drift, and reports that
look identical often have distinct root causes.

## Verdicts

- **Fixable-aligned** — a real defect whose fix helps the default (minimal-mode) user or
  recovers breakage, and fits the philosophy. Safe to implement.
- **Fixable-with-tension** — implementable, but needs a product/design decision first.
- **Unfixable-by-construction** — the request collides with a standing constraint (always-on
  VPN's idle cost; plaintext-DNS-only detection, which strict Private DNS/DoT defeats;
  UID-global attribution; no SSL interception). Respond to the reporter; do **not** add a
  knob to paper over it.
- **External-platform/OEM** — root cause is Android/OEM/work-profile; not fixable in-app.
- **Decline-philosophy** — a general-firewall or expert-knob request (constraint 1),
  which is NetGuard's role. The per-app "Exclude from VPN" toggle + Minimal-mode
  auto-excludes already cover breakage-recovery.

## Reusable close messages

**A — configurability proliferation / firewall feature (use NetGuard):**
> Thanks for the suggestion. TrackerControl is deliberately a focused tracker blocker, not
> a general-purpose firewall — its design prioritises simplicity and sensible defaults over
> fine-grained per-app/per-network configuration, and we explicitly avoid "Rethink-style"
> expert knobs unless they directly help users recover from breakage. The firewall-style
> control you're describing is exactly what NetGuard — the project TrackerControl is forked
> from — already provides, and keeping that role there is what lets TrackerControl stay lean
> and battery-friendly. If your goal is to stop a specific app breaking, the per-app "Exclude
> from VPN" toggle (in that app's tracker details) bypasses TrackerControl entirely for it.
> Closing as out of scope, but thank you for the thoughtful request.

**B — requires SSL interception (privacy-by-construction):**
> Thanks for the request. By design, TrackerControl never performs SSL/TLS interception — it
> only ever logs connection metadata, and this "privacy-preserving by construction" property
> is non-negotiable (it's also what lets the app run without root and stay trustworthy). What
> you're describing would require decrypting HTTPS via a local man-in-the-middle, which we
> will not add. Tracker detection therefore relies on DNS interception rather than payload
> inspection; even TLS/SNI parsing is confined to an opt-in research mode, because acting on
> it would leak your IP to the tracker before we could block. Closing as won't-fix-by-
> construction — this isn't a limitation we can lift without breaking the app's core privacy
> guarantee.
