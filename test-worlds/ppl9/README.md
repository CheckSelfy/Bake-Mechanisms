# Extracted PPL9 farm world

This is the shared, version-controlled source of the manual and headless lab. It contains **two actual cuboids extracted from the user-provided `ppl9-farm` save**, placed as four farms: an original and a comparison copy for each type. Native compressed Minecraft structure files occupy about 16 KB; controls reuse the same files. Paper generates a fresh, independent world from these files for every new test server. No original map download or Node dependency is needed to run the lab.

| Fixture | Original selection, inclusive | Lab mechanism, inclusive | Lab output barrel |
|---|---|---|---|
| `cactus` | `(19,64,18)` – `(28,103,26)` | `(0,64,0)` – `(9,103,8)` | `(2,65,-1)` |
| `cane` | `(168,73,-5)` – `(174,79,11)` | `(64,64,0)` – `(70,70,16)` | `(63,64,8)` |
| `cactus-control` | Same cactus selection | `(32,64,0)` – `(41,103,8)` | `(34,65,-1)` |
| `cane-control` | Same cane selection | `(96,64,0)` – `(102,70,16)` | `(95,64,8)` |

Each comparison copy is translated 32 blocks east, with separate mechanism/output chunks. Stand near `(48,64,24)` to cover all four with one player's normal random-tick radius. On upgrading an existing manual lab, controls are copied from its actual restored originals, including crop ages and inventories; the builder verifies exact initial block states and container contents. Existing original registrations and player edits are retained. Future restarts leave these copies alone.

The cactus tower has 32 cactus bases on eight levels. The cane farm has 14 planted positions, 14 pistons and two observers. The original redstone, crop ages, water, sand/soil, decoration and block entities inside each selection are retained in the `.nbt` files, at source DataVersion 4082 (Minecraft 1.21.3). Paper 26.3 loads the structures into the new world. No missing source datapacks are copied or required by this world builder. Nearby buildings, player data and unrelated entities are excluded.

The placed lab includes these deliberate adaptations, applied by the separate test plugin:

- The cactus farm's old double trapped chest is replaced by a north-facing hopper inside the selection, feeding a new external barrel. This gives `/bake` a supported output container outside the mechanism.
- The cane farm's two original collection hoppers are joined to an external barrel with a short hopper route. Crop harvesting redstone is retained.
- Three decorative cactus-edge water sources are removed. Two small glass caps outside each cane mechanism prevent collection water escaping. Productive internal water remains. The upgrade removes the existing exterior spill once; headless checks require that it does not return during normal operation or after restoration.
- Gold/lime blocks mark the two inclusive selection corners. The background is a clean stone plane. Daylight and weather are fixed, natural mob spawning is disabled, `randomTickSpeed=3`.

Exact selections/adaptations are in [farms.json](farms.json). [provenance.json](provenance.json) records source region hashes, output hashes and block counts. The original map is read only during extraction:

```text
python scripts/extract-farm-structures.py E:/Sites/ppl9-farm
```

To use the lab:

```text
python scripts/farm-lab.py run
python scripts/farm-lab.py headless --server run/farm-lab-headless-new
```

Run those sequentially; both listen on localhost port 25566. The saved manual world and plugin data live in ignored `run/farm-lab/`. Headless runs get separate fresh directories and include an extra review plugin; the manual copy contains only BakeMechanisms and the lab builder. The builder creates the farms only once, so manual changes survive clean restarts. A new `--server` directory resets to the same checked-in fixture source.

Use `/farmlab compare cactus 12000` or `/farmlab compare cane 12000` to record the before/after output of both farms over the same actual tick interval. Natural growth is random; identical mechanisms do not guarantee identical short-window yields. Compare average output over longer windows, and verify emulation against its measured profile separately.

Full hand-test instructions: [manual-farm-lab.md](../../docs/testing/manual-farm-lab.md).
