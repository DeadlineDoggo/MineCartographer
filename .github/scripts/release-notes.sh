#!/bin/sh
# Release notes (markdown) from conventional commit subjects.
#   sh .github/scripts/release-notes.sh TAG [PREV] [END]
# TAG   the tag being released. A full release (vX.Y.0[-alpha]) lists everything since the previous
#       full release; a nightly (vX.Y.Z, Z > 0) lists changes since the previous tag.
# PREV  override the start tag ("" = work it out). END  override the end (default TAG, e.g. HEAD).
# The "-alpha" suffix is ignored when deciding full or nightly.
set -eu
TAG=${1:?tag}; PREV=${2:-}; END=${3:-$TAG}
REPO=DeadlineDoggo/MineCartographer

base=${TAG#v}; base=${base%%-*}
case $base in *.*.0) full=1 ;; *) full=0 ;; esac

if [ -z "$PREV" ]; then
  for t in $(git tag --list 'v*' --sort=-creatordate); do
    [ "$t" = "$TAG" ] && continue
    if [ $full = 1 ]; then
      b=${t#v}; b=${b%%-*}
      case $b in *.*.0) PREV=$t; break ;; esac
    else
      PREV=$t; break
    fi
  done
fi

range=$END; [ -n "$PREV" ] && range="$PREV..$END"
subjects=$(git log --no-merges --format=%s "$range")

section() { # title, type regex
  out=$(printf '%s\n' "$subjects" | grep -E "^($2)(\([^)]*\))?!?: " \
    | sed -E 's/^[a-z]+\(([^)]*)\)!?: (.*)/- **\1:** \2/; s/^[a-z]+!?: (.*)/- \1/' || true)
  [ -n "$out" ] && printf '### %s\n%s\n\n' "$1" "$out"
  return 0
}

if [ $full = 1 ]; then echo "Full release${PREV:+, changes since $PREV}."; else echo "Nightly build${PREV:+, changes since $PREV}. May be unstable."; fi
echo
section "New" "feat"
section "Fixes" "fix"
section "Performance" "perf"
section "Documentation" "docs"
section "Build and packaging" "build|ci"
section "Maintenance" "chore|refactor|test|style"
other=$(printf '%s\n' "$subjects" | grep -vE '^(feat|fix|perf|docs|build|ci|chore|refactor|test|style)(\([^)]*\))?!?: ' | grep -v '^$' | sed 's/^/- /' || true)
[ -n "$other" ] && printf '### Other\n%s\n\n' "$other"
[ -n "$PREV" ] && echo "Full changelog: https://github.com/$REPO/compare/$PREV...$TAG"
echo
echo "_This is an alpha version, not finished. macOS builds are unsigned: right-click the app and choose Open the first time._"
exit 0
