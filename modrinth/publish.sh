#!/usr/bin/env bash
# Modrinth publish helper. DRY RUN by default.
#
#   bash modrinth/publish.sh                                   # check files and metadata only
#   MODRINTH_TOKEN_FILE=~/.modrinth-token bash modrinth/publish.sh --apply
#   MODRINTH_TOKEN_FILE=~/.modrinth-token bash modrinth/publish.sh --submit
#
# --apply  creates the project as a private DRAFT (if missing), sets the icon and gallery, and
#          uploads both versions (Fabric, Forge/NeoForge) with the same jar. Re-running is safe:
#          existing versions and gallery images are skipped.
# --submit sends the draft for Modrinth moderation (it becomes public after approval).
#
# Token scopes: Read user data, Create projects, Write projects, Create versions
# (https://modrinth.com/settings/pats). Keep it in a file; never paste it anywhere.
# MODRINTH_API overrides the API base, e.g. https://staging-api.modrinth.com/v2 for a test run.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dir="$root/modrinth"
mode="dry"
[[ "${1:-}" == "--apply" ]] && mode="apply"
[[ "${1:-}" == "--submit" ]] && mode="submit"
api="${MODRINTH_API:-https://api.modrinth.com/v2}"

die() { echo "ERROR: $*" >&2; exit 1; }
ok()  { echo "  ok  $*"; }
need_file() { [[ -f "$1" ]] && ok "$2" || die "missing $2 -> $1"; }
json() { python3 -c "import json,sys;print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$@"; }  # json FILE KEY

echo "1. files"
for f in project.json version-fabric.json version-forge.json description.md; do need_file "$dir/$f" "$f"; done
need_file "$dir/assets/icon-512.png" "icon"
jar_path="$(cd "$dir" && python3 -c "import os,sys;print(os.path.abspath(sys.argv[1]))" "$(json "$dir/version-fabric.json" file)")"
need_file "$jar_path" "mod jar ($(basename "$jar_path"))"
echo "      jar: $(wc -c < "$jar_path" | tr -d ' ') bytes, sha256 $(shasum -a 256 "$jar_path" | cut -c1-16)…"
icon_size="$(wc -c < "$dir/assets/icon-512.png" | tr -d ' ')"
(( icon_size <= 262144 )) && ok "icon ${icon_size} bytes (limit 256 KiB)" || die "icon larger than 256 KiB"

echo "2. metadata"
python3 - "$dir" <<'PY'
import json, os, sys
d = sys.argv[1]
p = json.load(open(f"{d}/project.json"))
for k in ["title", "slug", "project_type", "summary", "description_file", "categories",
          "client_side", "server_side", "license", "icon"]:
    if not p.get(k): sys.exit(f"ERROR: project.json missing {k}")
for g in p.get("gallery", []):
    if not os.path.isfile(os.path.join(d, g["file"])): sys.exit(f"ERROR: missing gallery file {g['file']}")
print(f"  ok  {p['title']} ({p['slug']}), {len(p.get('gallery', []))} gallery images")
for spec in ("version-fabric.json", "version-forge.json"):
    v = json.load(open(f"{d}/{spec}"))
    for k in ["name", "version_number", "version_type", "game_versions", "loaders"]:
        if not v.get(k): sys.exit(f"ERROR: {spec} missing {k}")
    print(f"  ok  {spec}: {v['name']} loaders={v['loaders']} "
          f"versions={v['game_versions'][0]}..{v['game_versions'][-1]} ({len(v['game_versions'])})")
PY

if [[ "$mode" == "dry" ]]; then
  echo "3. dry run complete, nothing uploaded. Next: MODRINTH_TOKEN_FILE=... bash modrinth/publish.sh --apply"
  exit 0
fi

token_file="${MODRINTH_TOKEN_FILE:-}"
[[ -n "$token_file" && -f "$token_file" ]] || die "set MODRINTH_TOKEN_FILE to a file containing your token"
token="$(tr -d '[:space:]' < "$token_file")"
ua="mc2shell-fix-publish (github.com/Ctrl-Creeper/minecraft2shell-fix)"
call() {  # call METHOD PATH [curl args...] -> prints body; dies on HTTP >= 400
  local method="$1" path="$2"; shift 2
  local out; out="$(mktemp)"
  local code; code="$(curl -s -o "$out" -w '%{http_code}' -X "$method" "$api$path" \
      -H "Authorization: $token" -H "User-Agent: $ua" "$@")"
  if (( code >= 400 )); then
    echo "ERROR: $method $path -> HTTP $code: $(cat "$out")" >&2; rm -f "$out"; exit 1
  fi
  cat "$out"; rm -f "$out"
}

echo "3. account"
# Informational only: the lookup needs the "Read user data" scope, publishing does not.
if user="$(call GET /user 2>/dev/null)"; then
  echo "  ok  logged in as $(printf '%s' "$user" | python3 -c 'import json,sys;print(json.load(sys.stdin)["username"])')"
else
  echo "  --  account lookup refused (token lacks \"Read user data\"?), continuing"
fi

slug="$(json "$dir/project.json" slug)"
status_code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: $token" -H "User-Agent: $ua" "$api/project/$slug")"

if [[ "$mode" == "submit" ]]; then
  [[ "$status_code" == "200" ]] || die "project $slug does not exist yet; run --apply first"
  call PATCH "/project/$slug" -H "Content-Type: application/json" -d '{"status":"processing"}' >/dev/null
  echo "4. submitted $slug for review: https://modrinth.com/project/$slug"
  exit 0
fi

echo "4. project"
if [[ "$status_code" == "200" ]]; then
  echo "  ok  $slug already exists, reusing it"
else
  data="$(python3 - "$dir" <<'PY'
import json, sys
d = sys.argv[1]
p = json.load(open(f"{d}/project.json"))
print(json.dumps({
    "slug": p["slug"], "title": p["title"], "description": p["summary"],
    "body": open(f"{d}/{p['description_file']}").read(),
    "categories": p["categories"], "additional_categories": [],
    "client_side": p["client_side"], "server_side": p["server_side"],
    "project_type": p["project_type"], "license_id": p["license"],
    "source_url": p.get("source_url"), "issues_url": p.get("issues_url"),
    "wiki_url": None, "discord_url": None, "donation_urls": [],
    "is_draft": True, "initial_versions": [],
}, ensure_ascii=False))
PY
)"
  call POST /project --form-string "data=$data" >/dev/null
  echo "  ok  created draft $slug"
fi
project="$(call GET "/project/$slug")"
project_id="$(printf '%s' "$project" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
echo "      id $project_id"

echo "5. icon and gallery"
call PATCH "/project/$project_id/icon?ext=png" -H "Content-Type: image/png" \
     --data-binary "@$dir/assets/icon-512.png" >/dev/null
ok "icon"
existing_gallery="$(printf '%s' "$project" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["gallery"]))')"
if [[ "$existing_gallery" != "0" ]]; then
  echo "  ok  gallery already has $existing_gallery images, skipping"
else
  python3 - "$dir" <<'PY' | while IFS=$'\t' read -r file featured query; do
import json, sys, urllib.parse
d = sys.argv[1]
for i, g in enumerate(json.load(open(f"{d}/project.json")).get("gallery", [])):
    q = {"ext": "png", "featured": str(bool(g.get("featured"))).lower(), "title": g.get("title", ""),
         "ordering": i}
    if g.get("description"): q["description"] = g["description"]
    print(f"{g['file']}\t{q['featured']}\t{urllib.parse.urlencode(q)}")
PY
    call POST "/project/$project_id/gallery?$query" -H "Content-Type: image/png" \
         --data-binary "@$dir/$file" >/dev/null
    ok "gallery $(basename "$file") (featured=$featured)"
  done
fi

echo "6. versions"
versions="$(call GET "/project/$project_id/version")"
for spec in version-fabric.json version-forge.json; do
  data="$(python3 - "$dir/$spec" "$project_id" "$versions" <<'PY'
import json, sys
v = json.load(open(sys.argv[1]))
existing = json.loads(sys.argv[3])
if any(e["version_number"] == v["version_number"] and sorted(e["loaders"]) == sorted(v["loaders"])
       for e in existing):
    print("SKIP"); sys.exit()
v.pop("file", None); v.pop("note", None)
v.update({"project_id": sys.argv[2], "file_parts": ["file"], "primary_file": "file"})
print(json.dumps(v, ensure_ascii=False))
PY
)"
  if [[ "$data" == "SKIP" ]]; then echo "  ok  $spec already uploaded, skipping"; continue; fi
  call POST /version --form-string "data=$data" -F "file=@$jar_path;type=application/java-archive" >/dev/null
  ok "uploaded $spec"
done

echo "7. done. Draft: https://modrinth.com/project/$slug"
echo "   Check it in the browser, then submit with: bash modrinth/publish.sh --submit"
