#!/bin/sh
# Release notes (markdown) from conventional commit subjects.
#   sh .github/scripts/release-notes.sh TAG [PREV] [END]
# TAG   the tag being released. A full release (vX.Y.0[-alpha]) lists everything since the previous
#       full release; a nightly (vX.Y.Z, Z > 0) lists changes since the previous tag.
# PREV  override the start tag ("" = work it out). END  override the end (default TAG, e.g. HEAD).
# The "-alpha" suffix is ignored when deciding full or nightly.
# DIST (env, optional) is the folder with the release files: the download table then shows their real names.
set -eu
TAG=${1:?tag}; PREV=${2:-}; END=${3:-$TAG}
REPO=${GITHUB_REPOSITORY:-DeadlineDoggo/MineCartographer}
VER=${TAG#v}
DIST=${DIST:-}

base=${TAG#v}; base=${base%%-*}
case $base in *.*.0) full=1 ;; *) full=0 ;; esac

if [ -z "$PREV" ]; then
  for t in $(git tag --list 'v*' --sort=-creatordate); do
    [ "$t" = "$TAG" ] && continue
    # only tags in this release's history (a nightly tagged on a feature branch is not)
    git merge-base --is-ancestor "$t" "$END" 2>/dev/null || continue
    if [ $full = 1 ]; then
      b=${t#v}; b=${b%%-*}
      case $b in *.*.0) PREV=$t; break ;; esac
    else
      PREV=$t; break
    fi
  done
fi

range=$END; [ -n "$PREV" ] && range="$PREV..$END"
# One subject per line: two messages typed into one commit subject ("... feat(x): ...") are split apart,
# and the version bump commits ("chore: release v...") are left out.
subjects=$(git log --no-merges --format=%s "$range" \
  | sed 's/ \(feat\|fix\|perf\|docs\|build\|ci\|chore\|refactor\|test\|style\)\([(:]\)/\n\1\2/g' \
  | grep -v '^chore: release v' || true)

# One section (### title) of the commits whose type matches the regex. Commits without a scope come first, together
# with scopes used only once ("- **scope:** text"); a scope used two or more times gets its own "#### Scope:" group
# below them, in the order the scopes first appear.
section() { # title, type regex
  out=$(printf '%s\n' "$subjects" | sed -n -E "s/^($2)(\(([^)]*)\))?!?: (.+)\$/\3\t\4/p" | awk -F'\t' '
    { scope[NR] = $1; text[NR] = $2; if ($1 != "") n[$1]++ }
    END {
      for (i = 1; i <= NR; i++) {
        if (scope[i] == "") print "- " text[i]
        else if (n[scope[i]] == 1) print "- **" scope[i] ":** " text[i]
      }
      for (i = 1; i <= NR; i++) {
        s = scope[i]
        if (s == "" || n[s] < 2 || done[s]) continue
        done[s] = 1
        printf "\n#### %s:\n", toupper(substr(s, 1, 1)) substr(s, 2)
        for (j = i; j <= NR; j++) if (scope[j] == s) print "- " text[j]
      }
    }')
  [ -n "$out" ] && printf '### %s\n%s\n\n' "$1" "$out"
  return 0
}

# The file in DIST matching a pattern, or the pattern itself (with * as a wildcard) when there is no DIST.
file() {
  if [ -n "$DIST" ]; then
    for f in "$DIST"/$1; do [ -e "$f" ] && { basename "$f"; return; }; done
  fi
  printf '%s' "$1"
}

echo "Map and chunk editor for Minecraft Java worlds, with 3D views and a model editor."
echo
echo "> **Alpha.** Not finished: back up your worlds and check an exported world in Minecraft before relying on it."
if [ $full = 0 ]; then
  echo ">"
  echo "> **Nightly build.** Fresh from development, it may be rough around the edges. Full releases are the \`vX.Y.0\` ones."
fi
echo

cat <<EOF2
## Downloads

Every package brings its own Java runtime: no Java needed.

| System | File | Install |
|---|---|---|
| Windows | \`$(file "*.msi")\` | run it; a newer one replaces the installed version |
| macOS, Apple silicon | \`$(file "*-macos-arm64.dmg")\` | open, drag to Applications; the first time right-click it and choose Open (not signed) |
| macOS, Intel | \`$(file "*-macos-x64.dmg")\` | same as above |
| Debian, Ubuntu | \`$(file "*_amd64.deb")\` / \`arm64.deb\` | \`sudo apt install ./minecartographer_*.deb\` |
| Fedora, openSUSE | \`$(file "*.x86_64.rpm")\` / \`aarch64.rpm\` | \`sudo dnf install ./minecartographer-*.rpm\` |
| Arch Linux | \`$(file "*-x86_64.pkg.tar.zst")\` / \`aarch64\` | \`sudo pacman -U minecartographer-*.pkg.tar.zst\` |
| any Linux | \`$(file "*-linux-x64.tar.gz")\` / \`linux-arm64\` | unpack, run \`MineCartographer/bin/MineCartographer\` |

The Linux packages install to \`/opt/minecartographer\` and add a menu entry. Checksums are in \`SHA256SUMS\`.

## What's new

EOF2
section "New" "feat"
section "Fixes" "fix"
section "Performance" "perf"
section "Documentation" "docs"
section "Build and packaging" "build|ci"
section "Maintenance" "chore|refactor|test|style"
other=$(printf '%s\n' "$subjects" | grep -vE '^(feat|fix|perf|docs|build|ci|chore|refactor|test|style)(\([^)]*\))?!?: ' | grep -v '^$' | sed 's/^/- /' || true)
[ -n "$other" ] && printf '### Other\n%s\n\n' "$other"
if [ -n "$PREV" ]; then
  echo "**Full changelog:** https://github.com/$REPO/compare/$PREV...$TAG"
else
  echo "First release."
fi
exit 0
