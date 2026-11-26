# Minecraft2Shell Fix NG

A client-side stopgap against the Minecraft "server resource pack font" remote code execution vulnerability ([bikini/minecraft2shell](https://github.com/bikini/minecraft2shell)).

Unlike the original [sunrisemc-tw/minecraft2shell-fix](https://github.com/sunrisemc-tw/minecraft2shell-fix), this mod does not disable font hinting, preserving font rendering quality.

This mod backports Mojang's fix for Minecraft 26.3 by replacing FreeType with the patched version (2.15.0) instead of disabling hinting altogether.

The binaries shipped with this mod are extracted from official Mojang builds, as listed in the [Minecraft 26.3 version manifest](https://piston-meta.mojang.com/v1/packages/8696584e8d2da5726487597e6c5777480387ec22/26.3.json).
Please be noticed that linux on ARM64 is not supported yet because Mojang does not provide fixed binary on linux ARM64.

Supports Minecraft 1.20.5 to 26.3, NeoForge + Forge + Fabric. Mojang has fixed this in 26.3 version, so if you use 26.3 version and your launcher havn't pinned LWJGL version, you don't need this mod.

Also, this mod is not vibe slop :) It is entirely human-written.

## Licensing

Copyright (C) 2026 Ctrl Creeper and Xu Colin.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

## FreeType License

This mod is based in part on the work of the FreeType Team.

The bundled FreeType 2.15.0 binaries are redistributed under the FreeType License (FTL).

These binaries are extracted from official Mojang builds and redistributed without modification.
