---
name: Loom 1.16+ no longer provides modImplementation
description: As of Fabric Loom ~1.16-SNAPSHOT (MC 26.x era), modImplementation is gone — use implementation for all mod deps including fabric-loader, fabric-api, and third-party mods
type: project
---

As of Fabric Loom 1.16-SNAPSHOT (the version used with Minecraft 26.1.2), the `modImplementation` Gradle configuration is no longer registered by Loom. The official fabric-example-mod template confirms this: all mod dependencies — including `fabric-loader`, `fabric-api`, and third-party mods fetched from Modrinth — use plain `implementation`.

**Why:** Loom's dependency model was simplified; the old `modImplementation`/`modApi` split is gone from the current template.

**How to apply:** When the user declares any mod dependency (Modrinth, CurseForge maven, local JARs), use `implementation`, not `modImplementation`. Correcting any build error mentioning `Could not find method modImplementation()` means switching to `implementation`.

Also: the current template requires a `loom { splitEnvironmentSourceSets() }` block with a `mods { }` entry naming the mod's source sets.
