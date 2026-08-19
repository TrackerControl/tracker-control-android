# `agents/` — shared agent configuration

Everything an AI coding agent needs that isn't tied to one vendor lives here, so
Claude Code, Codex and anything else read the same material.

```
AGENTS.md                 always loaded; entry point for every tool
CLAUDE.md                 one line: @AGENTS.md (Claude Code's entry point)
agents/docs/*.md          NOT loaded — read only when AGENTS.md's table says to
agents/hooks/*.sh         tool-neutral scripts, invoked by whatever hook system exists
.claude/settings.json     Claude-specific wiring only; no guidance content
```

## The rules that keep this useful

1. **AGENTS.md stays short.** It is loaded in full, every session, by every tool.
   If a piece of guidance only matters in one situation, it belongs in
   `agents/docs/` with a row in AGENTS.md's "read these when the situation
   applies" table.
2. **Every doc is reachable.** Nothing loads `agents/docs/` on its own — a doc
   that no always-loaded file points at will simply never be read. Adding a doc
   means adding its row to the table.
3. **No dangling pointers.** A mistyped path silently removes guidance instead of
   failing loudly. Check paths when you move files.
4. **Don't restate volatile facts.** Tool versions, SDK levels and library
   versions belong in the build files; point at them rather than copying them, or
   they go stale and mislead.
5. **Vendor directories hold wiring, not content.** `.claude/` may contain
   settings and pointers; the guidance and scripts it points at live here.

## Hooks

`hooks/gradle-quiet.sh` forces `./gradlew` runs to quiet logging so build output
stays readable. It works two ways — as a Claude Code `PreToolUse(Bash)` hook, or
as a plain filter (`--rewrite '<command>'`) for tools without a hook system.
Prefer that shape for anything added here: a script that works standalone, with
the vendor-specific glue kept thin.
