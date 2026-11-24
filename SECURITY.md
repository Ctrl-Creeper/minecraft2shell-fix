# Security policy

## What this project is

A client-side stopgap for the Minecraft font resource-pack attack ("minecraft2shell"). It is **not**
an official fix and it makes no guarantee — see the disclaimer in the README.

## Reporting a problem in this project

If you find a way to bypass the mitigation, please open a
[private security advisory](https://github.com/sunrisemc-tw/minecraft2shell-fix/security/advisories/new)
rather than a public issue.

Useful in a report:

- Minecraft version, loader, and how the mod/agent is installed
- the font that bypasses it (or a link), and the effective `-Dm2sfix.*` settings
- what the bypass achieves (rendering degradation restored? code execution?)

## What is in scope

- Bypasses of the safe channel (`policy=safe`): any path that gets hinted rendering back for an
  attacker-supplied font
- Crashes, hangs or rendering breakage caused by the guard itself
- Mistakes in the scanner that cause a **miss** (a hostile font reported clean)

## What is out of scope

- The underlying FreeType bug — that is upstream; the fix is FreeType ≥ 2.15.0 and a Minecraft
  version that ships it
- Reports produced only by running the published proof of concept against a live client you do not
  own
- "The dialog looks scary" — it is meant to
