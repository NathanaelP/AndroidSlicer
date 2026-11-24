#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Fetch CuraEngine and all of its dependencies at the versions pinned in
# versions.env into $WORK/src. Safe to re-run: existing checkouts are kept.
#
# Usage: engine/fetch-sources.sh            (WORK defaults to ~/mobileslicer-build)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=versions.env
source "$HERE/versions.env"
WORK="${WORK:-$HOME/mobileslicer-build}"
SRC="$WORK/src"
mkdir -p "$SRC"

# git_fetch <dir> <repo> <tag-or-commit>: shallow checkout of one exact ref.
git_fetch() {
  local dir="$SRC/$1" repo="$2" ref="$3"
  if [ -d "$dir/.git" ]; then
    echo "have   $1 @ $(git -C "$dir" rev-parse --short HEAD)"
    return
  fi
  echo "fetch  $1 @ $ref"
  git init -q "$dir"
  git -C "$dir" remote add origin "$repo"
  git -C "$dir" -c advice.detachedHead=false fetch -q --depth 1 origin "$ref"
  git -C "$dir" -c advice.detachedHead=false checkout -q FETCH_HEAD
}

# url_fetch <file> <url> <sha256>: download a tarball and verify it.
url_fetch() {
  local out="$SRC/$1" url="$2" sha="$3"
  if [ ! -f "$out" ]; then
    echo "fetch  $1"
    curl -sSfL -o "$out.part" "$url"
    mv "$out.part" "$out"
  fi
  echo "$sha  $out" | sha256sum -c --quiet - || { echo "checksum mismatch: $1" >&2; exit 1; }
}

git_fetch curaengine      "$CURAENGINE_REPO"      "$CURAENGINE_REF"
# sparse_fetch <dir> <repo> <ref> <path>...: shallow checkout of only some files.
sparse_fetch() {
  local dir="$SRC/$1" repo="$2" ref="$3"; shift 3
  if [ ! -d "$dir/.git" ]; then
    echo "fetch  $(basename "$dir") (sparse) @ $ref"
    git init -q "$dir"
    git -C "$dir" remote add origin "$repo"
    git -C "$dir" -c advice.detachedHead=false fetch -q --depth 1 origin "$ref"
  else
    echo "have   $(basename "$dir") (sparse)"
  fi
  # Always (re)apply the path list, so adding paths here updates old checkouts.
  git -C "$dir" sparse-checkout set --no-cone "$@"
  git -C "$dir" -c advice.detachedHead=false checkout -q FETCH_HEAD 2>/dev/null \
    || git -C "$dir" -c advice.detachedHead=false checkout -q HEAD
}

# Base setting definitions plus the Elegoo Neptune 4 / 4 Pro machine files.
sparse_fetch cura "$CURA_REPO" "$CURA_REF" \
  /resources/definitions/fdmprinter.def.json \
  /resources/definitions/fdmextruder.def.json \
  /resources/definitions/elegoo_base.def.json \
  /resources/definitions/elegoo_neptune_4.def.json \
  /resources/definitions/elegoo_neptune_4pro.def.json \
  /resources/extruders/elegoo_extruder_0.def.json \
  /resources/variants/elegoo/elegoo_neptune_4pro/ \
  /resources/quality/elegoo/neptune_4/
sparse_fetch fdm_materials "$FDM_MATERIALS_REPO" "$FDM_MATERIALS_REF" \
  /generic_pla_175.xml.fdm_material /LICENSE
git_fetch ultimaker-index "$ULTIMAKER_INDEX_REPO" "$ULTIMAKER_INDEX_REF"
git_fetch scripta         "$SCRIPTA_REPO"         "$SCRIPTA_REF"
git_fetch fmt             "$FMT_REPO"             "$FMT_REF"
git_fetch spdlog          "$SPDLOG_REPO"          "$SPDLOG_REF"
git_fetch range-v3        "$RANGEV3_REPO"         "$RANGEV3_REF"
git_fetch rapidjson       "$RAPIDJSON_REPO"       "$RAPIDJSON_REF"
git_fetch stb             "$STB_REPO"             "$STB_REF"
git_fetch wagyu           "$WAGYU_REPO"           "$WAGYU_REF"
git_fetch mapbox-geometry "$MAPBOX_GEOMETRY_REPO" "$MAPBOX_GEOMETRY_REF"
git_fetch mapbox-variant  "$MAPBOX_VARIANT_REPO"  "$MAPBOX_VARIANT_REF"
git_fetch onetbb          "$ONETBB_REPO"          "$ONETBB_REF"
git_fetch zlib            "$ZLIB_REPO"            "$ZLIB_REF"
git_fetch libpng          "$LIBPNG_REPO"          "$LIBPNG_REF"

url_fetch boost.tar.bz2 "$BOOST_URL"   "$BOOST_SHA256"
url_fetch clipper.zip   "$CLIPPER_URL" "$CLIPPER_SHA256"

if [ ! -d "$SRC/boost" ]; then
  echo "unpack boost (headers only)"
  mkdir -p "$SRC/boost.tmp"
  tar -xjf "$SRC/boost.tar.bz2" -C "$SRC/boost.tmp" --strip-components=1 --wildcards '*/boost/*'
  mv "$SRC/boost.tmp" "$SRC/boost"
fi
if [ ! -d "$SRC/clipper" ]; then
  echo "unpack clipper"
  mkdir -p "$SRC/clipper.tmp"
  (cd "$SRC/clipper.tmp" && unzip -q "$SRC/clipper.zip" 'cpp/clipper.*')
  mv "$SRC/clipper.tmp" "$SRC/clipper"
fi

echo "sources ready in $SRC"
