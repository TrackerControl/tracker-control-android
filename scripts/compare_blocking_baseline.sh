#!/bin/zsh
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
TAG_NAME=${1:-2025121801}
TEST_CLASS="net.kollnig.missioncontrol.data.BlockingBaselineSubsetTest"
GRADLE_TEST_TASK=":app:testFdroidDebugUnitTest"
TMP_DIR=$(mktemp -d /tmp/tracker-control-baseline.XXXXXX)
WORKTREE_DIR="$TMP_DIR/worktree"

cleanup() {
  if [[ -d "$WORKTREE_DIR/.git" || -f "$WORKTREE_DIR/.git" ]]; then
    git -C "$ROOT_DIR" worktree remove --force "$WORKTREE_DIR" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "Running shared baseline subset on current branch"
(
  cd "$ROOT_DIR"
  ./gradlew "$GRADLE_TEST_TASK" --tests "$TEST_CLASS"
)

echo
echo "Preparing isolated worktree for tag $TAG_NAME"
git -C "$ROOT_DIR" worktree add --detach "$WORKTREE_DIR" "$TAG_NAME" >/dev/null

if [[ -f "$ROOT_DIR/local.properties" ]]; then
  cp "$ROOT_DIR/local.properties" "$WORKTREE_DIR/local.properties"
fi

mkdir -p "$WORKTREE_DIR/app/src/test/java/net/kollnig/missioncontrol/data"
cp "$ROOT_DIR/app/src/test/java/net/kollnig/missioncontrol/data/BlockingBaselineSubsetTest.java" \
  "$WORKTREE_DIR/app/src/test/java/net/kollnig/missioncontrol/data/BlockingBaselineSubsetTest.java"

if ! grep -q "testImplementation 'junit:junit:4.13.2'" "$WORKTREE_DIR/app/build.gradle"; then
  python3 - "$WORKTREE_DIR/app/build.gradle" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
needle = "dependencies {\n"
replacement = "dependencies {\n    testImplementation 'junit:junit:4.13.2'\n"
if needle not in text:
    raise SystemExit("Could not find dependencies block in app/build.gradle")
path.write_text(text.replace(needle, replacement, 1))
PY
fi

echo "Running shared baseline subset on tag $TAG_NAME"
(
  cd "$WORKTREE_DIR"
  ./gradlew "$GRADLE_TEST_TASK" --tests "$TEST_CLASS"
)

echo
echo "Baseline subset passed on current branch and tag $TAG_NAME."
echo "Behavioral differences outside this shared subset still need manual review."
