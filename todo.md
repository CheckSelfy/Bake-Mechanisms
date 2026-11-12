The work splits cleanly into three parallel implementation streams, plus an integration/review stream.

## Stream A — production-rate correctness

This is the highest-priority stream: it blocks claiming #12 is complete.

- Reproduce the 61-tick mismatch using a Paper integration test that counts the resolved double-chest inventory through the API.

- Verify the exact profile arithmetic: profile rate × elapsed server ticks ÷ 20, including fractional carry.

- Audit `StasisEmulationService` to prove one, and only one, emulation stream exists per farm.

- Test startup/recovery/rollback/repeated failed stasis attempts for duplicate schedulers or duplicate production.

- Test a full double chest: prove the buffer is bounded, production pauses, and resume does not replay or multiply items.

- Version or migrate persisted `emulation.dat` if a correction changes its interpretation.

- Add a 61-tick regression case using the observed `gold1` profile rate and assert the precise expected item delta.

Owner: one person focused on `emulation/`, storage, and Paper output resolution.

Status: closed on `fix/todo-streams`. Root cause: production and profiling counted scheduler ticks, which Paper keeps running during `/tick freeze`, so a frozen server still produced (the #12 benchmark credited ~1,100 frozen ticks inside 61 game ticks). `GameTickGate` now gates the per-tick task on `ServerTickManager.isRunningNormally()`. `StasisEmulationService.start` rejects a second stream and `StasisService` tracks `producing` per farm. `emulation.dat` keeps format 2 deliberately: it stores only fractions in [0,1) and whole buffered items, neither of which depends on how ticks are counted. Evidence: `Gold1RateRegressionTest` (61-tick, any-window, 1,200-tick, carry across restart, full double chest), `StasisServiceTest` (recovery/rollback/repeat/failed restore), `stasisSmoke` → `EMULATION_RATE_SMOKE_PASSED` (61 game ticks after 200 frozen server ticks = 7 rotten flesh; counting scheduler ticks gave 32) and `FULL_DOUBLE_CHEST_SMOKE_PASSED` (buffer ≤ 2 over 400 ticks, no replay after freeing).

## Stream B — native mob snapshot stability

This can proceed independently from production-rate work.

- Support ordinary non-persistent spawner mobs without manual persistence edits.

- Handle zombified-piglin chicken-jockey passenger trees without manual dismounting.

- Replace detached passenger-location validation with validation matching the actual restore lifecycle.

- Make stasis automatically retry if it encounters an entity being spawned, discarded, mounted, or dismounted.

- Ensure capture, removal, and filler placement have a safe stable handoff with no surviving originals.

- Add real-Paper tests for zombified piglins, chickens, jockeys, native kills, stasis, restart, and unstasis restoration.

- Add user-facing progress/status messages while automatic stabilization is retrying.

Owner: one person focused on `stasis/`, `PaperEntitySnapshots`, and native Paper integration tests.

Status: closed. This stream was implemented twice in parallel — `feature/BM-34-gleb` (merged to `main` as #38) and `fix/todo-streams` — and the merge keeps the union of both, not one side.

Two root causes, one per implementation, and both are real: Paper's `isValid()` is false for a mob in its 20-tick death animation, so `serializeEntity(PASSENGERS)` rejects such a tree; and Paper refuses to serialize a non-persistent entity at all without `EntitySerializationFlag.FORCE`. `feature/BM-34-gleb` fixed only the first and worked around the second by forcing `setPersistent(true)` on every spawner piglin in the benchmark world, which made the benchmark stop testing ordinary despawning mobs. That workaround is removed; `FORCE` (from `fix/todo-streams`) carries the item, and the NBT keeps `Bukkit.persist=false`, so a restored spawner mob stays non-persistent.

From `feature/BM-34-gleb`: standalone corpses are skipped by capture, swept from the cuboid by `remove`, and `remove` re-fetches every saved id to prove no original survives. From `fix/todo-streams`: the `FORCE` flag, per-instability retry budgets (`TransientStasisException.maxRetries` — 10 ticks for a piston stroke, 100 for an entity in transition, instead of a flat 100 for both), and the richer player feedback (chat once per distinct reason, action-bar countdown). One retry driver survives, under `feature/BM-34-gleb`'s documented name `StasisStabilizer`; `StasisEntryRetry` and `StasisInTransitionException` are gone.

One semantic conflict had to be decided rather than merged: when the detached native copy disagrees with the live passenger tree, `feature/BM-34-gleb` treated it as retryable and `fix/todo-streams` as a permanent refusal. `requireStable` proves the live tree consistent in the *same tick* as serialization, so no concurrent change is possible in between: it is an NBT fidelity problem, permanent. Retrying it would burn 100 ticks and then report "did not stabilize" instead of naming the real cause. `fix/todo-streams`' per-member validation (root position exact, passenger vehicle identity) is kept and `passengerMissingFromTheNativeCopyIsRetryable` is updated accordingly.

Evidence (all on the merged tree): `stasisSmoke` → `NATIVE_MOB_STASIS_SMOKE_PASSED` (stasis entered after 20 automatic retries of a dying jockey rider, 5 native golem kills during warmup) and `NATIVE_PIGLIN_STASIS_SMOKE_PASSED` (23 members, waited 20 ticks, `deathKeptTreeMounted=true`); `nativeMobSmoke` → `NATIVE_MOB_RESTART_PASSED` (19 non-persistent mobs and jockeys restored from the durable snapshot after a clean restart); `entityNbtSmoke` → 96 entity types across a real restart. See `docs/development/stasis-integration.md`.

## Stream C — benchmark and lab reliability

This can also proceed in parallel.

- Make `resetFarmLab` remove all stale registrations belonging to prior generated lab worlds.

- Keep distinct mechanism chunks for all benchmark farms.

- Provide explicit safe AFK/profile positions so users do not enter protection buffers.

- Add a lab smoke test that registers all three farms after reset.

- Add a lab smoke test that profiles all three farms and enters stasis without operator cleanup commands.

- Add a test helper that clears and reads an entire resolved double chest through Paper API; do not rely on `/data` for benchmark accounting.

- Make the benchmark runner record game ticks, initial inventory, final inventory, profile rates, and Spark artifacts automatically.

Owner: one person focused on `farmLab/`, Gradle tasks, fixture scripts, and test-world documentation.

Status: closed on `fix/todo-streams`. `LabWorldHistory` records every lab world the server ever generated next to (not inside) the deleted data folder, so `resetFarmLab` finds stale registrations from earlier worlds. The three benchmark farms sit 17 blocks apart in distinct chunks so their protection belts (mechanism + 2) do not overlap and all three profile at once; `safeAfkPosition()` computes the shared AFK pad and throws at build time if it falls inside any belt or out of spawner range (the earlier pad at 14,89,14 was inside all three). `ResolvedOutputInventory` reads a whole resolved double chest through the Paper API instead of `/data`. `MobBenchmark` records game ticks, both inventories, profile rates and the Spark artifact.

## Stream D — integration and acceptance evidence

This starts with scaffolding now, but its final run depends on A, B, and C.

- Create one end-to-end acceptance scenario: native originals → profile → stasis → exact 1,200-tick production check → unstasis.

- Require no `/tick freeze`, `/kill`, persistence mutation, or rider dismount commands.

- Capture original and stasis Spark evidence under identical player position and farm set.

- Verify 20 TPS recovery is retained after the correctness fixes.

- Update the #12 result record with final PASS/FAIL status, exact artifact links, and any remaining limitations.

Owner: one reviewer/test engineer; they should not implement the underlying fixes.

Status: closed, re-run on the merged tree. `fix/todo-streams` recorded its acceptance before Stream B was merged, so that evidence did not cover the combined stasis-entry code; the review was re-run after the merge. Result: original 8.521 TPS / 115.603 median MSPT → stasis 20.001 TPS / 0.267 median MSPT, production over exactly 1,200 game ticks exact in all 10 farm/item combinations, `forbidden-operator-commands: []`, all three farms restored to `NEEDS_PROFILING`. Evidence: `docs/testing/evidence/2026-11-12-mob-acceptance-merged/` (the pre-merge run is kept alongside in `2026-11-12-mob-acceptance/`).

Two caveats are recorded in that directory's `README.md`: Paper's watchdog trips after the verdict while the *restored originals* are saved at 8.5 TPS (the load stasis removes — not a plugin fault), which made the harness exit non-zero and skip its own artifact copy, so the files were copied manually; and Spark on Windows falls back to its built-in Java engine.

Fixed while re-running: `MobPerformanceReviewPlugin` asserted the client stood on the AFK pad, but `FarmLabPlugin.onJoin` teleports a player who joined another world to the lab centre five ticks after the join, which can land after the review's `farmlab visit`. The review passed or failed depending on that race. It now re-issues the visit, bounded, instead of failing.

## Suggested dependency order

- Start Stream A, B, and C immediately in parallel.

- Stream D can build the acceptance harness immediately, but should run its final evidence pass only after A, B, and C merge.

- Merge order: A and B first in either order; C can merge independently; then D runs the complete benchmark against the combined result.

- Do not close #12 until Stream D proves both conditions together: TPS/MSPT improvement and exact production-rate preservation.
