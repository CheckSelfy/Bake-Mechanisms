# Test verification and issue #11 audit — 2026-11-11

**Follow-up:** the driver defects below were fixed and the expanded lifecycle passed. See [acceptance follow-up](acceptance-2026-11-11.md) for current coverage and remaining boundaries. This report preserves the original failing evidence.

Source revision: `a376e25`. Java 25.0.5+8, Python 3.14.7, pinned Paper 26.3 build 246.
The working tree was clean before verification. The only code change is in the separate correctness driver.

## Fresh execution results

| Check | Result | Local evidence |
| --- | --- | --- |
| `gradlew.bat build --rerun-tasks --console=plain` | PASS: 197 Java tests, zero failures/errors/skips | `build/test-results/test/`, `run/verification-build.log` |
| `python -m unittest discover -s scripts -p 'test_*.py' -v` | PASS: 9 tests | Terminal output |
| `gradlew.bat stasisSmoke entityNbtSmoke --console=plain` | PASS: native snapshot, failure/retry, transport and natural-input checks; restart restored 96 entities across 95 entity types | `run/verification-native.log`, `run/entity-nbt-smoke/logs/` |
| `python scripts/farm-lab.py headless --server run/verification-farm-lab-20261111 --port 25567` | PASS: four farms, exact accounting and restoration, no exterior water | `run/verification-farm-lab.log`, `run/verification-farm-lab-20261111/` |
| `python scripts/correctness-review.py --server run/verification-short-20261111 --short-profile-only` | PASS: short and empty profiles rejected | `run/verification-short.log`, `run/verification-short-20261111/` |
| `python scripts/correctness-review.py --server run/verification-correctness-fixed-20261111` | FAIL: lifecycle assertions through C11 passed, but final fixture marker validation failed; runner exited 1 | `run/verification-correctness-fixed.log`, `run/verification-correctness-fixed-20261111/` |

## Test-driver defect found

The first full run (`run/verification-correctness-20261111/`) failed at step 5:
`full output did not accumulate bounded pending production`.

Its measured cactus rate was `0.041666666666666664` items per 20 ticks. After the independently verified 4000-tick production phase, its fraction was `0.333333333333332800`. Another 200 ticks produces only about 0.4167 items, so there is still no whole item to buffer. The driver incorrectly assumed that every natural profile would block within 200 ticks. Exact output/fraction accounting and entity restoration had already passed.

The driver now waits for the first nonempty buffer, with a 7200-tick timeout, then compares the complete saved emulation state after another 3600 ticks. The timeout exceeds the time needed for one item at the lowest positive rate from a 3600-tick profile. The `C08_BLOCKED` marker records the actual wait. This retains the failure signal for missing production and for production continuing while blocked.

The fresh rerun reached `C08_BLOCKED` after **620 ticks**, then passed the full 3600-tick pause, real process restart, exact resumed accounting, block restoration, and C11 repeated cycle/deletion. This directly exercises the case that the old 200-tick assumption could not handle.

## Remaining failing check

The rerun still exited **1** at its final `bakefixtures check`:
`FIXTURES_ERROR Internal snapshot marker changed: blocks`.

The driver's `command()` helper clears internal containers before profiling and reinserts the marker only before a valid stasis request. In step 8 the block farm is profiled, then deliberately invalidated to `NEEDS_PROFILING`; the following stasis request is correctly refused. That path never reinserts the test marker, so final fixture validation finds it missing. This is a mismatch between test setup and final fixture expectations; immediate post-restoration marker checks had passed.

The Java driver logs `CORRECTNESS_PASSED` after dispatching the fixture check because successful command dispatch does not mean the command's validation succeeded. The Python runner correctly catches `FIXTURES_ERROR` and fails the overall run. **Do not treat that Java success marker as an overall PASS.**

Remaining driver work: reconcile the deliberately cleared marker with final fixture validation, and make the final success marker depend on successful fixture validation. These defects remain open; the current full suite is not all green.

## Issue #11 disposition: keep open

Compared the [GitHub issue](https://github.com/CheckSelfy/Bake-Mechanisms/issues/11), its [local record](../../.scratch/bake-mechanisms-mvp/issues/11-correctness-review.md), the [acceptance scenarios](scenarios.md), and the recorded [results](results.md).

The earlier user-reported hand test is recorded, but does not establish every remaining acceptance case. Outstanding evidence remains:

- C01/C03/C05: real client selection/protection interactions, both game modes, interaction range, entering/teleporting, conflicting selections and both halves of a double output chest. Headless runs synthesize Bukkit interaction events.
- C04: verify completion/refusal messages in the client.
- C07: natural production of supported items with individual properties. The injected damaged, named, enchanted sword tests normalization, but does not cover the natural armor fixture described by C07.
- C08: player/hopper extraction and piston/output-reuse interactions; the driver frees space through the inventory API.
- C09/C12: complete production-command cases for restoring with a full output and leftover buffer, and retrying a failed restore with active emulation. Separate native and unit checks do not establish the combined scenario.
- C10: restart with an unblocked output and a measured offline interval; the existing restart uses an already blocked output and paused production.

The acceptance gaps above are not newly demonstrated production defects; the driver failures are additional work before claiming a fully passing automated review. The ticket's result-recording requirement also calls for the final findings to be recorded in its tracker comments. This verification is recorded locally; no new GitHub comment was posted.
