#!/usr/bin/env bash
# Force ./gradlew runs to quiet logging, so build output stays small enough for
# an agent to read without burning its context on Gradle's progress noise.
# A log-level flag the caller chose explicitly always wins.
#
# Two ways to use it, so it is not tied to one agent tool:
#
#   1. As a Claude Code PreToolUse(Bash) hook — reads the tool-call JSON on
#      stdin and emits the rewritten command as JSON. Wired up in
#      .claude/settings.json.
#
#   2. As a plain filter for any other tool (Codex, scripts, a shell alias):
#         agents/hooks/gradle-quiet.sh --rewrite './gradlew assembleGithubDebug'
#      prints the rewritten command on stdout.
#
# No-ops silently if jq is missing (hook mode), leaving the command untouched.
set -uo pipefail

# Rewrite a command string: add -q to each ./gradlew segment unless the caller
# already picked a log level. Echoes the (possibly unchanged) command.
rewrite() {
  local cmd="$1"

  if [[ ! "$cmd" =~ (^|[[:space:]])\./gradlew($|[[:space:]]) ]]; then
    printf '%s' "$cmd"
    return 1
  fi

  local segments
  segments=$(printf '%s' "$cmd" | grep -oE '\./gradlew[^&|;]*')
  if printf '%s' "$segments" |
    grep -qE '(^|[[:space:]])(-q|-w|-i|-d|--quiet|--warn|--info|--debug)($|[[:space:]])'; then
    printf '%s' "$cmd"
    return 1
  fi

  printf '%s' "$cmd" | sed -E 's#\./gradlew#./gradlew -q#g'
  return 0
}

if [[ "${1:-}" == "--rewrite" ]]; then
  rewrite "${2:-}"
  echo
  exit 0
fi

# Claude Code PreToolUse hook mode.
input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null)

if [[ -z "$cmd" ]]; then
  echo '{}'
  exit 0
fi

if ! new_cmd=$(rewrite "$cmd"); then
  echo '{}'
  exit 0
fi

encoded=$(printf '%s' "$new_cmd" | jq -Rs .)
printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","updatedInput":{"command":%s}}}\n' "$encoded"
