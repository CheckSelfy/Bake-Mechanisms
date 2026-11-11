# Issue #11 acceptance follow-up — 2026-11-11

This follows the [earlier failed verification](verification-2026-11-11.md). Most changes are isolated test tooling. Production snapshot support now also admits audited `ARROW` entities: native skeleton farms otherwise cannot snapshot their fired projectiles. The new cross-JVM regression first failed on the old whitelist, then passed with arrow damage, critical/piercing state, pickup mode, shooter UUID and potion state intact.

## Fixed driver failures

- Wait for actual blocked production, with a timeout, before measuring the 3600-tick pause. Natural profiles need not produce a whole item in 200 ticks.
- Restore the deliberately cleared fixture marker on the repeated profiling/invalidation path. Actual post-restoration marker assertions remain in place.
- Require a successful `FIXTURES_CHECK_OK` response before logging final success. The runner also rejects fixture errors before or after a success marker.
- Allow for the first emulation update not yet being persisted immediately after stasis begins.
- Protocol controls use immutable request files, bounded asynchronous assertions, client-tick-end packets, and fail if either player disconnects. Client logs capture received system messages and action bars. A disconnected player cannot count as successful movement protection.

## Expanded real-Paper coverage

| Gap | New evidence |
| --- | --- |
| C01 selection and conflicts | Real wire commands, left/right clicks and sneaking in survival and creative; out-of-reach click rejected; mechanism preserved; overlapping registration and an output inside the mechanism rejected without partial records; legacy-command messages received. |
| C01 double output chest | Native double chest spanning a chunk boundary; selecting either half toggles one logical output; one registration contains both physical blocks. |
| C03/C04 client messages and protection | Wire movement requires a newly received protection action bar; wire breaking and container opening are denied during profiling. Cancellation, successful completion and empty-profile refusal are read from received packets. |
| C05 filler/output protection | Actual survival/creative break packets against filler; creative break packet against the output; output remains accessible during stasis; wire restore succeeds. |
| C07 armor normalization | Both controlled named/damaged/enchanted chestplates and **naturally dropped damaged enchanted armor** are verified. Imported skeleton and zombie farms complete production, profiling, stasis, exact emulation and complete restore. Native armor retains its original properties; newly emulated armor is plain, with no XP. |
| C08 extraction | Native hopper extracts a full stack from the protected output. A wire container shift-click moves a stack into the non-OP player's actual inventory. |
| C09 restore with full output | Restore with a nonempty production buffer; saved drain equals that exact buffer, stays unchanged for 200 blocked ticks, then delivers exactly once after space is freed; no continuing emulation and no leaked tickets. |
| C10 unblocked restart | Third JVM starts after a measured minimum 3-second offline interval. With output space available, saved inventory and emulation state are unchanged before the first update; subsequent production matches exact tick accounting. The existing blocked-output restart and unfinished-profile cancellation remain covered. |
| C12 production-command failures | Obstructing `snapshot.bin` with a nonempty directory causes an actual save failure before world replacement. On a separate restore, preserving the real manifest while obstructing its pathname causes deactivation to fail **after native placement**: production commands roll back to filler, preserve snapshot/buffer/fractions, remove restored entities, and successfully retry after the obstruction is removed. No production fault hooks or reflection. |

The restore fault is a real storage/deactivation failure after placement, rather than an injected partial-placement failure. Native partial-placement failures remain covered by `stasisSmoke`; do not conflate the two.

## Reproduce

Use fresh directories and an already accepted/verified Paper cache. Java 25 and the Python standard library are sufficient.

```text
python scripts/correctness-review.py --server run/acceptance-full
python scripts/correctness-review.py --server run/acceptance-client --client-acceptance-only --port 25568
python scripts/correctness-review.py --server run/acceptance-short --short-profile-only --port 25569
python scripts/correctness-review.py --server run/acceptance-mobs --mob-farms-only --port 25570
python -m unittest discover -s scripts -p "test_*.py" -v
```

The full run uses three cleanly separated server processes. `--port` allows independent runs without touching the manual farm server. Test-only filesystem obstructions are confined to newly generated registrations in the disposable review directory; the original manifest is retained and restored in `finally`.

## Results and remaining scope

- Full expanded lifecycle: **PASS**, `run/acceptance-20261111-02/`, runner exit 0. Final fixture validation also passed. Offline minimum: `3.0002461000003677` seconds, plus JVM startup.
- Packet-client acceptance: **PASS**, `run/acceptance-clients-20261111-09/`, runner exit 0. Includes actual world-change and disconnect/reconnect selection resets, powered piston attempt, cross-chunk double chest, and both game modes. Final marker at tick 15360. Both clients remained online except for the explicitly expected reconnects.
- Short-profile acceptance: **PASS**, `run/acceptance-short-20261111/`, runner exit 0, final marker at tick 3616.
- Mob-farm acceptance: **PASS**, `run/acceptance-mobs-20261111-07/`, runner exit 0. Warm-up collected a naturally dropped enchanted golden chestplate. The measured profile included copper boots with damage 124, Feather Falling III, Projectile Protection III and Unbreaking II. Exact accounting over 1680 emulation ticks passed; restoration matched all blocks/inventories, spawner delay and 33 skeleton-farm / 4 zombie-farm entity UUIDs. Final marker at tick 7328. See [fixture details and adaptations](../../test-worlds/ppl9-mobs/README.md).
- Native arrow restart audit: **PASS**, 97 entities across 96 supported types, repeated cycle; `run/acceptance-arrow-after-fix.log`. The before-fix native run failed specifically on unsupported `ARROW`, retained as `run/acceptance-arrow-before-fix.log`.
- Native `stasisSmoke` rerun after the production change: **PASS**, `run/acceptance-final-stasis.log`.
- Python regressions: **15 PASS**. Forced rebuild (`build correctnessJar fixturesJar --rerun-tasks`): **197 Java tests PASS**, no failures/errors/skips. Log: `run/acceptance-final-build.log`.

Saved process logs were independently revalidated against the final runner. Compact logs, binary hashes and the measured offline interval are checked in as [evidence](evidence/2026-11-11-acceptance.txt). Native smoke/entity-restart and extracted-farm tests passed earlier against the same unchanged production source, as recorded in the [original verification](verification-2026-11-11.md).

Protocol automation does not establish rendered Minecraft UI/layout; the user confirmed that manual/visual acceptance on 2026-11-11. The previous natural-armor fixture gap is resolved. Selection reset on logout/world change and a powered piston attempt are now exercised natively; the piston result includes vanilla's immovable-container protection and is not an isolated proof of the plugin listener alone.

**Acceptance complete.** At the user's explicit request, all acceptance checkboxes were marked and [issue #11](https://github.com/CheckSelfy/Bake-Mechanisms/issues/11) was closed as completed. [Final acceptance record](https://github.com/CheckSelfy/Bake-Mechanisms/issues/11#issuecomment-6805116735). Changes remain uncommitted in the workspace; this does not claim a published release.
