#!/usr/bin/env bash
# Modrinth publish helper — DRY RUN by default.
#
#   bash modrinth/publish.sh              # checks every file/field, prints what it would do
#   MODRINTH_TOKEN=... bash modrinth/publish.sh --apply
#
# The token needs PROJECT_CREATE + CREATE_VERSION (https://modrinth.com/settings/pats).
# Never paste the token into chat; export it in your shell or read it from a file.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dir="$root/modrinth"
apply=0
[[ "${1:-}" == "--apply" ]] && apply=1

die() { echo "ERROR: $*" >&2; exit 1; }
ok()  { echo "  ok  $*"; }
need_file() { [[ -f "$1" ]] && ok "$2" || die "missing $2 -> $1"; }

echo "1. files"
need_file "$dir/project.json" "project.json"
need_file "$dir/version-fabric.json" "version-fabric.json"
need_file "$dir/version-forge.json" "version-forge.json"
need_file "$dir/description.md" "description.md"
need_file "$dir/assets/icon-512.png" "icon-512.png"
need_file "$dir/assets/banner-1280x720.png" "banner-1280x720.png"
need_file "$dir/../docs/images/hinting-comparison.png" "hinting-comparison.png"
jar="$(python3 -c "import json;print(json.load(open('$dir/version-fabric.json'))['file'])")"
# resolve the path relative to the modrinth/ directory, not the current one
jar_path="$(cd "$dir" && python3 -c "import os,sys;print(os.path.abspath(sys.argv[1]))" "$jar")"
need_file "$jar_path" "mod jar ($jar)"
echo "      jar size: $(stat -c%s "$jar_path") bytes"

echo "2. project.json fields"
python3 - "$dir/project.json" <<'PY'
import json, sys
p = json.load(open(sys.argv[1]))
required = ["title", "slug", "project_type", "summary", "description_file",
            "categories", "client_side", "server_side", "license", "icon"]
missing = [k for k in required if not p.get(k)]
if missing:
    print("  MISSING:", missing); sys.exit(1)
if len(p["summary"]) > 256:
    print(f"  summary too long: {len(p['summary'])} > 256"); sys.exit(1)
print(f"  ok  title={p['title']} slug={p['slug']} summary={len(p['summary'])} chars")
print(f"  ok  categories={p['categories']} client={p['client_side']} server={p['server_side']}")
PY

echo "3. version metadata"
python3 - "$dir/version-fabric.json" "$dir/version-forge.json" "$jar_path" <<'PY'
import json, sys, hashlib, os
jar = sys.argv[3]
digest = hashlib.sha512(open(jar, "rb").read()).hexdigest()
for path in sys.argv[1:3]:
    v = json.load(open(path))
    for key in ("version_number", "name", "release_channel", "game_versions", "loaders", "file"):
        if not v.get(key):
            print(f"  MISSING {key} in {os.path.basename(path)}"); sys.exit(1)
    if not os.path.exists(os.path.join(os.path.dirname(path), v["file"])):
        print(f"  jar path does not resolve in {os.path.basename(path)}"); sys.exit(1)
    print(f"  ok  {os.path.basename(path)}: {v['name']} loaders={v['loaders']} "
          f"versions={len(v['game_versions'])}")
print(f"  sha512={digest}")
PY

if [[ $apply -eq 0 ]]; then
  cat <<'MSG'

4. dry run complete — nothing was uploaded.

   To publish for real:
     export MODRINTH_TOKEN=...        # your PAT, not in chat
     bash modrinth/publish.sh --apply

   The script creates the project, then the two versions (same jar, different loaders),
   and finally uploads the icon and gallery images.
MSG
  exit 0
fi

[[ -n "${MODRINTH_TOKEN:-}" ]] || die "MODRINTH_TOKEN is not set"

api="https://api.modrinth.com/v2"
auth=(-H "Authorization: $MODRINTH_TOKEN" -H "User-Agent: sunrisemc-tw/minecraft2shell-fix")

echo "4. creating project"
slug="$(python3 -c "import json;print(json.load(open('$dir/project.json'))['slug'])")"
existing="$(curl -s -o /dev/null -w '%{http_code}' "$api/project/$slug")"
if [[ "$existing" == "200" ]]; then
  echo "  project $slug already exists — skipping creation"
else
  payload="$(python3 - "$dir/project.json" "$dir/description.md" <<'PY'
import json, sys
p = json.load(open(sys.argv[1]))
p["body"] = open(sys.argv[2]).read()
p.pop("description_file", None)
p.pop("icon", None)
p.pop("gallery", None)
print(json.dumps(p, ensure_ascii=False))
PY
)"
  curl -sf -X POST "$api/project" "${auth[@]}" -H "Content-Type: application/json" \
       -d "$payload" >/dev/null || die "project creation failed"
  echo "  created /project/$slug"
fi

echo "5. uploading versions"
for spec in version-fabric.json version-forge.json; do
  meta="$(python3 - "$dir/$spec" <<'PY'
import json, sys
v = json.load(open(sys.argv[1]))
v.pop("file", None); v.pop("note", None)
v["project_id"] = json.load(open(sys.argv[1].replace(sys.argv[1].split('/')[-1], "project.json")))["slug"]
print(json.dumps(v, ensure_ascii=False))
PY
)"
  curl -sf -X POST "$api/version" "${auth[@]}" \
       -F "data=$meta" -F "file=@$jar_path" >/dev/null \
    || die "version upload failed for $spec"
  echo "  uploaded $spec"
done

echo "6. done — open https://modrinth.com/mod/$slug and check the gallery/icon by hand"
