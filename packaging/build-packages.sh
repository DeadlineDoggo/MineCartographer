#!/bin/sh
# Builds the .deb, .rpm and Arch packages from the portable Linux archive.
#   sh packaging/build-packages.sh VERSION ARCH TARBALL [OUTDIR]
# VERSION e.g. 0.4.0-alpha, ARCH amd64 or arm64, TARBALL = MineCartographer-VERSION-linux-*.tar.gz
# Needs `nfpm` or Docker.
set -eu
VERSION=${1:?version}; ARCH=${2:?arch amd64|arm64}; TARBALL=${3:?tarball}; OUT=${4:-dist}
cd "$(dirname "$0")/.."

root=build/pkgroot
rm -rf "$root"; mkdir -p "$root/opt/minecartographer" "$OUT"
tar -xzf "$TARBALL" -C "$root/opt/minecartographer" --strip-components=1
test -x "$root/opt/minecartographer/bin/MineCartographer" || { echo "archive has no bin/MineCartographer"; exit 1; }
cp packaging/minecartographer.desktop "$root/"
cp app/icons/app.png "$root/minecartographer.png"
cp LICENSE "$root/"

export VERSION ARCH
for fmt in deb rpm archlinux; do
  if command -v nfpm >/dev/null 2>&1; then
    nfpm package -p "$fmt" -f packaging/nfpm.yaml -t "$OUT/"
  else
    docker run --rm -e VERSION -e ARCH -v "$PWD":/work -w /work goreleaser/nfpm:v2.41.0 \
      package -p "$fmt" -f packaging/nfpm.yaml -t "$OUT/"
  fi
done
ls -l "$OUT"
