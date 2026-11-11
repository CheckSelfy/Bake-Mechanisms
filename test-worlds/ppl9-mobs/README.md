# PPL9 mob-farm fixtures

Two actual mechanisms extracted read-only from the user-provided `E:/Sites/ppl9-farm`:

- Skeleton spawner: source `(28,-33,-328)` through `(40,-9,-316)`.
- Zombie spawner: source `(-395,-25,78)` through `(-382,-11,91)`.

`farms.json` records dimensions, destinations and every adaptation. `provenance.json` records original region hashes and compressed structure hashes. Structure assets preserve the original block/tile NBT; the test builder adapts only its disposable copies. No persistent entities are imported.

Run on Java 25, with an accepted verified Paper cache:

```text
python scripts/correctness-review.py --server run/mob-review --mob-farms-only --port 25570
```

The test uses native spawners, AI, combat, equipment generation, death loot and hopper collection. It never supplies equipment or changes drop chances. Six tame invulnerable wolves automate the skeleton killing cell; a lava blade automates the zombie killing cell. Historical stock, pre-existing spawned mobs and their finite equipment are cleared before profiling, leaving renewable spawners and persistent wolf producers. Unused ender chests and comparator tiles from the bypassed sorter are removed from the test copy because they are not audited snapshot types.

Hard difficulty, aged chunks, 80–120-tick spawner delays and server tick sprinting shorten the test; these are functional checks, **not vanilla yield or performance benchmarks**. Warm-up waits for actual damaged enchanted armor to reach the output. Profiling uses complete 3600-tick windows and may repeat up to eight times rather than inserting artificial production.

Before stasis, the test waits for native death animations to finish so all captured actors are valid. Original XP orbs are cleared as ignored byproducts before measuring emulation; XP emulation is never allowed. Arrows remain in the snapshot and are audited across a separate JVM restart.

During stasis the instrument withdraws real output every 20 ticks to avoid capacity saturation, tracks every removed item, and independently verifies whole production and exact decimal fractions for every profiled type. It checks a native metadata-bearing original separately from newly generated plain armor, unchanged player XP, and absence of XP orbs. Restore compares every selected block and internal inventory, spawner delay and all entity UUIDs.

The runner leaves worlds, logs and native armor samples in the selected run directory. A read-only snapshot preflight fails before warm-up if an unsupported tile is introduced.
