#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"
INDEX_BASE="$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages"

# -----------------------------------------------------------------------------
# 同梱ランタイムのバージョン固定（GPL corresponding source 整合性のため）
#
# APK に同梱するバイナリと assets/legal/sources/ の対応ソースは必ず同一
# バージョンでなければならない。Termux stable が新版へ進んだ場合、ここで
# 意図的にビルドを失敗させ、静かなバージョンドリフトを防ぐ。
#
# 更新時は次もすべて同じ版へ揃えること:
#   - scripts/prepare-distribution-legal.sh （対応ソース URL / SHA-256）
#   - app/build.gradle.kts （verifyDistributionLegal の required リスト）
#   - .github/workflows/android-ci.yml / publish-release.yml （APK 検証 grep）
#   - THIRD_PARTY_NOTICES.md / docs/PROOT-SOURCE-OFFER.md
# -----------------------------------------------------------------------------
PROOT_VERSION="5.1.107.92"
LIBANDROID_SHMEM_VERSION="0.7"
LIBTALLOC_VERSION="2.4.3"

for tool in curl dpkg-deb python3 patchelf readelf file sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Missing build tool: $tool" >&2
    exit 1
  }
done

mkdir -p "$OUT"

echo "Downloading Termux aarch64 package index..."
INDEX_FOUND=false
for suffix in xz bz2 gz plain; do
  case "$suffix" in
    xz) url="$INDEX_BASE.xz"; out="$WORK/Packages.xz" ;;
    bz2) url="$INDEX_BASE.bz2"; out="$WORK/Packages.bz2" ;;
    gz) url="$INDEX_BASE.gz"; out="$WORK/Packages.gz" ;;
    plain) url="$INDEX_BASE"; out="$WORK/Packages.raw" ;;
  esac
  echo "Trying $url"
  if curl -fsSL --retry 2 --retry-delay 1 "$url" -o "$out"; then
    case "$suffix" in
      xz) xz -dc "$out" > "$WORK/Packages" ;;
      bz2) bzip2 -dc "$out" > "$WORK/Packages" ;;
      gz) gzip -dc "$out" > "$WORK/Packages" ;;
      plain) cp "$out" "$WORK/Packages" ;;
    esac
    INDEX_FOUND=true
    echo "Using Termux package index format: $suffix"
    break
  fi
done

if [[ "$INDEX_FOUND" != true ]]; then
  echo "Could not download any Termux aarch64 Packages index format." >&2
  exit 1
fi

test -s "$WORK/Packages" || { echo "Downloaded Termux package index is empty." >&2; exit 1; }

package_record() {
  local package="$1"
  python3 - "$WORK/Packages" "$package" <<'PY'
import sys
path, wanted = sys.argv[1:]
text = open(path, encoding="utf-8", errors="replace").read()
for stanza in text.split("\n\n"):
    fields = {}
    current = None
    for line in stanza.splitlines():
        if not line:
            continue
        if line[0].isspace() and current:
            fields[current] += "\n" + line[1:]
        elif ": " in line:
            current, value = line.split(": ", 1)
            fields[current] = value
    if fields.get("Package") != wanted or fields.get("Architecture") != "aarch64":
        continue
    filename = fields.get("Filename")
    sha = fields.get("SHA256")
    version = fields.get("Version", "unknown")
    if filename and sha:
        print(f"{filename}\t{sha}\t{version}")
        sys.exit(0)
print(f"Could not find stable aarch64 package {wanted}", file=sys.stderr)
sys.exit(1)
PY
}

download_package() {
  local package="$1"
  local expected_version="$2"
  local dest="$WORK/$package"
  mkdir -p "$dest"

  local record filename sha version deb
  record="$(package_record "$package")"
  IFS=$'\t' read -r filename sha version <<< "$record"
  deb="$WORK/${package}.deb"

  if [[ "$version" != "$expected_version" ]]; then
    {
      echo "ERROR: Termux stable の $package は $version ですが、CCFA は $expected_version に固定しています。"
      echo "同梱バイナリと対応ソース (GPL corresponding source) のバージョンが食い違うため中断します。"
      echo "対応: このスクリプト冒頭のバージョン固定を $version へ更新し、"
      echo "      scripts/prepare-distribution-legal.sh の対応ソース URL / SHA-256、"
      echo "      app/build.gradle.kts・.github/workflows/*.yml・THIRD_PARTY_NOTICES.md・"
      echo "      docs/PROOT-SOURCE-OFFER.md の参照もすべて同じ版へ揃えてください。"
    } >&2
    exit 1
  fi

  echo "Fetching $package $version"
  curl -fL --retry 3 --retry-delay 2 "$TERMUX_REPO/$filename" -o "$deb"
  echo "$sha  $deb" | sha256sum -c -
  dpkg-deb -x "$deb" "$dest"
  printf '%s\t%s\t%s\n' "$package" "$version" "$sha" >> "$WORK/runtime-packages.tsv"
}

download_package proot "$PROOT_VERSION"
download_package libandroid-shmem "$LIBANDROID_SHMEM_VERSION"
download_package libtalloc "$LIBTALLOC_VERSION"

PREFIX="data/data/com.termux/files/usr"
PROOT_SRC="$WORK/proot/$PREFIX/bin/proot"
LOADER_SRC="$WORK/proot/$PREFIX/libexec/proot/loader"
SHMEM_SRC="$(find "$WORK/libandroid-shmem" -type f -name 'libandroid-shmem.so' -print -quit)"
TALLOC_SRC="$(find "$WORK/libtalloc" -type f -name 'libtalloc.so*' | sort -V | tail -n1)"

for f in "$PROOT_SRC" "$LOADER_SRC" "$SHMEM_SRC" "$TALLOC_SRC"; do
  test -f "$f" || { echo "Required Termux runtime file missing: $f" >&2; exit 1; }
done

cp "$PROOT_SRC" "$OUT/libproot.so"
cp "$LOADER_SRC" "$OUT/libproot-loader.so"
cp "$SHMEM_SRC" "$OUT/libandroid-shmem.so"
cp "$TALLOC_SRC" "$OUT/libtalloc.so"
chmod 755 "$OUT/libproot.so" "$OUT/libproot-loader.so"
chmod 644 "$OUT/libandroid-shmem.so" "$OUT/libtalloc.so"

while read -r needed; do
  case "$needed" in
    libtalloc.so.*)
      patchelf --replace-needed "$needed" libtalloc.so "$OUT/libproot.so"
      ;;
  esac
done < <(patchelf --print-needed "$OUT/libproot.so")

patchelf --set-rpath '$ORIGIN' "$OUT/libproot.so"

NEEDED="$(patchelf --print-needed "$OUT/libproot.so")"
echo "PRoot DT_NEEDED:"
printf '%s\n' "$NEEDED"
echo "$NEEDED" | grep -q '^libandroid-shmem.so$'
echo "$NEEDED" | grep -q '^libtalloc.so$'

INTERP="$(patchelf --print-interpreter "$OUT/libproot.so")"
echo "PRoot interpreter: $INTERP"
test "$INTERP" = "/system/bin/linker64"

file "$OUT/libproot.so" "$OUT/libproot-loader.so" "$OUT/libandroid-shmem.so" "$OUT/libtalloc.so"
readelf -h "$OUT/libproot.so" | grep -E 'Machine:.*AArch64'
readelf -h "$OUT/libproot-loader.so" | grep -E 'Machine:.*AArch64'

mkdir -p "$ROOT/app/src/main/assets"
cp "$WORK/runtime-packages.tsv" "$ROOT/app/src/main/assets/termux-runtime-packages.tsv"

echo "Packaged Termux Android PRoot runtime:"
cat "$ROOT/app/src/main/assets/termux-runtime-packages.tsv"
sha256sum \
  "$OUT/libproot.so" \
  "$OUT/libproot-loader.so" \
  "$OUT/libandroid-shmem.so" \
  "$OUT/libtalloc.so"
