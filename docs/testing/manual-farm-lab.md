# Hand testing the extracted farm world

The same [checked-in farm fixtures](../../test-worlds/ppl9/README.md) build both the manual world and the headless test world. These are two small selections from `E:/Sites/ppl9-farm`: a cactus tower and an automatic sugar-cane farm. The original map is not modified.

## Start and join

From `E:\Sites\Bake-Mechanisms`:

```powershell
python scripts/farm-lab.py run
```

The script finds this workspace's downloaded JDK 25 automatically when `JAVA_HOME`/Java are absent, builds the current plugin and test-world helper, and prepares `run/farm-lab/`. It reuses the project's accepted Minecraft EULA and verified Paper cache; on another machine, first pass `--accept-eula` after accepting the EULA. Java 25 and Python 3.11+ are needed. The current server is Paper 26.3 build 246.

In your launcher choose **Minecraft Java Edition 26.3**. Open **Multiplayer → Direct Connection**, enter **`127.0.0.1:25566`**, then join. Use a normal username. This local server uses offline mode, so a Microsoft login is not required. The Bukkit plugin runs on this Paper server; opening the map in Singleplayer does not load BakeMechanisms.

The first join takes you to the farm lab. You do not need OP. If a server is already running on that address, join it instead of starting another copy.

## Comparison copies

Each original has a copy 32 blocks east: `cactus-control` starts at `(32,64,0)` with output `(34,65,-1)`; `cane-control` starts at `(96,64,0)` with output `(95,64,8)`. All four have independent mechanism/output chunks. Existing labs upgrade in place without replacing your originals or registrations; the controls initially copy their exact current blocks, crop ages, internal inventories and output contents. Outside water is cleaned up and the openings that caused the spills are fixed.

Use `/farmlab setup` to stand near the middle `(48,65,24)`, within random-tick distance of all four. Keep `*-control` running physically while you profile/bake the corresponding original. The same `visit` and `corner` commands accept all four IDs.

Before measuring, choose `/farmlab normal` or `/farmlab fast`, profile the original under that setting, and keep it unchanged. Ensure enough free output space, then run:

```text
/farmlab compare cactus 12000
/farmlab compare cane 12000
```

Each command observes both output barrels over the same **12000 actual ticks** (about 10 minutes at TPS 20). It records start/end counts, ticks and growth settings in `plugins/BakeFarmLab/comparisons/` and prints each delta. Stay near the middle; do not add/remove output items during the observation. Starting inventory is subtracted, so previously collected items do not count as new production. This observer does not empty outputs or change any farm state.

Crop growth and cactus item destruction are random. Matching mechanisms/starting state does **not** imply equal finite harvests. Compare averages over longer windows; check exact emulated production against the saved rate/fractions separately. This is also why a short three-minute profile can differ from a later physical control's measured rate. A full barrel or external extraction makes a simple inventory-delta comparison unsuitable; record those events separately.

## First full cycle: cactus

1. Run `/farmlab setup`. This enables creative mode for preparing the selection, while your account remains non-OP. Run `/farmlab list` to see both mechanisms and output coordinates.
2. Run `/bake wand`. Hold the Blaze Rod selector in your main hand.
3. Run `/farmlab corner cactus 1`. Look at the **gold block** `(0,64,0)` and **left-click** it with the selector.
4. Run `/farmlab corner cactus 2`. You will fly near the top of the tower. Look at the **lime concrete** `(9,103,8)` and **right-click** it with the selector.
5. Run `/farmlab corner cactus output`. Look at the **barrel** `(2,65,-1)` and **Shift + right-click** it with the selector.
6. Run `/bake register`. The result must be `NEEDS_PROFILING`. Copy the UUID from the message or `/bake list`. Below, replace `<UUID>` with that actual UUID; do not type the angle brackets.
7. Run `/farmlab visit cactus` to stand **outside** the selected region. For survival-mode acceptance use `/farmlab survival` now. Stay near the farm so its plants receive random ticks. First verify it delivers cactus to the external barrel without adding items or using a probe. A few minutes with no output should be recorded as a failure/blocked measurement, rather than assumed successful.
8. For a practical functional test, run `/farmlab fast` **before** profiling. This explicitly changes crop `randomTickSpeed` from 3 to 30, helping the original cane farm produce a batch within the fixed profiling window. Record that setting; it is not a vanilla-yield benchmark. Use `/farmlab normal` for ordinary crop growth, and do not change growth speed during a measurement. Now run `/bake profile --id <UUID>`. Stay nearby for **3600 server ticks**, about **3 minutes** at normal TPS. During profiling, try opening the output barrel, entering the region, and changing a block. Those actions should be blocked. Once finished, `/bake info --id <UUID>` should show `READY` and a positive cactus profile. A no-production rejection is a real result; record it and retry only after confirming the original farm works.
9. From outside the region run `/bake stasis --id <UUID>`. The complete selected tower becomes bedrock; the external barrel stays usable. Check `/bake info --id <UUID>` reports `STASIS`. Observe new cactus accumulating. Try breaking filler/output in survival, then use `/farmlab creative` and repeat. Both should be protected. Finish by returning outside with `/farmlab visit cactus`.
10. Run `/bake unstasis --id <UUID>`. The cactus, water, collector and corner markers should return. `/bake info --id <UUID>` should show `NEEDS_PROFILING`, without the old profile. Trying `/bake stasis --id <UUID>` again should refuse until you profile again.
11. Record the result, for example `/farmlab note cactus C01/C03/C05/C09 PASS - actual clicks, UI and protection checked`. Use `FAIL` or `BLOCKED` with the observed problem when appropriate.

Do not use `/farmlab corner` while profiling or in stasis to try bypassing the guard: those helper teleports still go through the normal Bukkit teleport events and may be refused. `/farmlab visit` is the safe outside position.

## Second farm and extra checks

Repeat with `cane` instead of `cactus`: gold `(64,64,0)`, lime `(70,70,16)`, output `(63,64,8)`. The barrel receives sugar cane. The original farm batches harvesting through two observers, so a three-minute measurement at normal growth can be quiet. Use the explicit fast-growth setting for functional testing; retain normal growth when investigating actual vanilla yields. Check actual output rather than expecting a fixed yield.

For **full output/resume**, while in stasis fill every output slot, wait, then remove a stack. Production should resume without an enormous backlog or dropped items. Check extraction by both hand and a hopper on separate fresh copies if you change collection geometry.

For **creator rights**, use a second client with a different username: it should not control the first player's UUID or see it in tab completion. Switching your own username creates a different creator on this offline server; keep your creator name unchanged across restarts.

For **restart**, put a registered farm into stasis, record its profile and output count, then run `/farmlab stop` (or `stop` in the server console). Wait for the server to finish saving. Start the same `python scripts/farm-lab.py run`, reconnect using the same username, and confirm stasis remains active and restoration works. Test unfinished profiling with the same clean stop/restart: it must return to `NEEDS_PROFILING`. The lab builder preserves the existing world and loads it before plugin recovery.

For **delete**, with the original restored, `/bake unregister --id <UUID>` should ask for confirmation; `/bake unregister --id <UUID> confirm` should remove the registration. Deletion in stasis must refuse. Confirming deletion can discard a remaining production buffer, as the command warns.

These two extracted farms do not naturally produce armor or experience. They do not complete the natural-armor normalization scenario C07. The full acceptance matrix is [scenarios.md](scenarios.md).

## Notes, reset and headless use

`/farmlab note <text>` writes a dated observation to `run/farm-lab/plugins/BakeFarmLab/manual-notes.txt`. Include your farm UUID, scenario, expected/actual result and any relevant inventory counts. Screenshots can be kept alongside that local run. A useful starting checklist is C01 actual clicks/selection, C03 UI restrictions, C05 protection in both modes, C08 extraction, C09 restoration, C10 restart, C11 confirmation/creator rights.

To start a clean copy, stop the current server and use a **new directory**:

```text
python scripts/farm-lab.py run --server run/farm-lab-manual-02
```

Keep the previous copy if you want its notes. Never restore just the world while leaving different BakeMechanisms farm data alongside it.

For the automatic check on the same extracted structure files:

```text
python scripts/farm-lab.py headless --server run/farm-lab-headless-02
```

This starts two minimal non-OP clients on a fresh copy and verifies all four farms. It warms up for at least 12000 actual ticks at ordinary `randomTickSpeed=3`, waiting up to 36000 for a natural harvest from the cane batches when necessary. It then switches to the explicitly logged `randomTickSpeed=30` functional setting, profiles for 3600 ticks, verifies stasis output/fractions for 2000 ticks, then restores and compares every block state and internal inventory. It also verifies the pair-observation commands and requires no water outside the mechanisms after warm-up/restoration. It uses `tick rate 100` to reduce wall time. Neither the fast-growth profile nor its yield is claimed as a vanilla performance benchmark. Gold/lime selection inputs are synthesized events in headless mode; the hand test above exercises actual client inputs. The review plugin is omitted from the manual server.

Full automatic logs are `fixture-console.log`, `clients.log`, `logs/latest.log`; plugin hashes are in `lab-environment.json`. A missing expected product, rejected profile, incomplete filler, accounting error or changed restored state fails the test. The final success marker is `FARM_LAB_HEADLESS_PASSED`.
