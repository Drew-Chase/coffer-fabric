---
name: Coffer inventory & transfer performance redesign
description: The mod's core initiative — lazy inventory loading + event-driven (non-polling) item transfers, built via heavy block-entity storage mixins
type: project
---

Coffer is a from-scratch Fabric mod (started as example scaffold) whose central goal is a two-part performance redesign of how inventories store data and how items move between them:

1. **Lazy inventory loading** — region/chunk files hold only a stub `{UUID, BlockPos, rotation}`; full block-entity + item data lives in `world/inventories/<uuid>` and loads only on interaction.
2. **Event-driven transfers** — transfer entities (hopper/dropper/hopper minecart) react to inventory "dirty" events and cache their decision (incl. negative caching) instead of polling per tick.

**Why:** performance — eliminate per-chunk-load deserialization of inventory contents and per-tick hopper scanning.

**How to apply:** This is a high-risk, deeply-invasive, mixin-heavy redesign that fights several vanilla assumptions. When advising, proactively flag: (a) comparator/redstone reads vs. lazy loading; (b) save/crash consistency across the two-source-of-truth split (region stub + side store); (c) hopper minecart uses the entity storage path, not the block-entity path; (d) vanilla hopper timing parity under event-driven transfers; (e) the modern ReadView/WriteView/Codec serialization model — confirm exact APIs for the target version via genSources rather than guessing. The full implementation plan lives in plan.md at the repo root.
