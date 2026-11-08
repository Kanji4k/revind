# Rewind (Phase 1) – Fabric 1.21.11 client mod

Keys (rebind in Options > Controls > Rewind): **F8** start/stop recording, **F9** clip last X s, **F10** save whole shadow buffer.
Clips go to `.minecraft/rewind/` as `.avi` (MJPEG). Settings: Mod Menu > Rewind, or `config/rewind.json`.

## Versions to double-check (gradle.properties + workflow)
- loom_version  (fabricmc.net/develop) – must support 1.21.11
- Gradle in .github/workflows/build.yml – must satisfy that Loom
- loader_version, fabric_version (ends with +1.21.11), modmenu_version (Modrinth, 1.21.11 build)

## Get the jar from GitHub
1. Create a repo, upload this folder's contents (keep `.github/`), commit to main.
2. Actions tab > latest "build" run > Summary > Artifacts > download `rewind-jar` (zip containing rewind-1.0.0.jar).
3. Put the jar in `.minecraft/mods/` with Fabric Loader, Fabric API and (optional) Mod Menu for 1.21.11.
