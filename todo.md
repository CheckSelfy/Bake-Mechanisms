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

Status (branch `feature/BM-34-gleb`): implemented. Root cause: Paper's `isValid()` is false for a mob in its 20-tick death animation, and `serializeEntity(PASSENGERS)` rejects such a tree as "invalid or non-persistent". Standalone corpses are skipped and discarded with the originals; transitional trees raise `EntityInTransitionException`, which `StasisStabilizer` retries every tick for up to 100 ticks with player progress messages. Passenger-coordinate validation is replaced by vehicle-topology validation. `remove` proves no saved original survives. Evidence: `stasisSmoke` → `NATIVE_PIGLIN_STASIS_SMOKE_PASSED`; `entityNbtSmoke` restart includes a piglin chicken jockey. See `docs/development/stasis-integration.md`.

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

## Stream D — integration and acceptance evidence

This starts with scaffolding now, but its final run depends on A, B, and C.

- Create one end-to-end acceptance scenario: native originals → profile → stasis → exact 1,200-tick production check → unstasis.

- Require no `/tick freeze`, `/kill`, persistence mutation, or rider dismount commands.

- Capture original and stasis Spark evidence under identical player position and farm set.

- Verify 20 TPS recovery is retained after the correctness fixes.

- Update the #12 result record with final PASS/FAIL status, exact artifact links, and any remaining limitations.

Owner: one reviewer/test engineer; they should not implement the underlying fixes.

## Suggested dependency order

- Start Stream A, B, and C immediately in parallel.

- Stream D can build the acceptance harness immediately, but should run its final evidence pass only after A, B, and C merge.

- Merge order: A and B first in either order; C can merge independently; then D runs the complete benchmark against the combined result.

- Do not close #12 until Stream D proves both conditions together: TPS/MSPT improvement and exact production-rate preservation.
