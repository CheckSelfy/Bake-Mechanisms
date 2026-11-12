# PPL9 high-load mob-farm world

This disposable world is the high-load input for the performance review in issue #12. It is built from **16 independent copies** of the real skeleton-spawner mechanism extracted from the user-provided `E:/Sites/ppl9-farm` save. The same source structure and adaptations used by the small PPL9 mob lab are used here; this folder only defines the scalable layout.

Skeleton spawners were selected over the supported crop and animal fixtures because each active plot has native spawner work, skeleton AI, wolf combat, drops and hopper transport. It therefore creates a useful server-load baseline without inventing an unsupported mechanism. The plugin supports the audited living-entity set in `PaperEntitySnapshots`; this fixture exercises `SKELETON`, `WOLF` and temporary `ITEM` entities. The imported PPL9 fixtures also cover cactus, sugar cane and zombie spawners, while the older synthetic fixture builder covers cactus and chickens.

The lab builder creates `farm_lab_mob_performance` on first start and places 16 plots in a 4-by-4 grid. Each inclusive mechanism is `(x,64,z)` through `(x+12,88,z+12)` and has an external double chest at `(x-1,71,z+5..6)`; spacing is 32 blocks, so no mechanisms or outputs share chunks. Plot names are `mobperf01` through `mobperf16`.

Use `python scripts/farm-lab.py run`, then `/farmlab visit mobperf01`, `/farmlab corner mobperf01 1|2|output`, and the normal `/bake` workflow to register/profile/stasize the plots. `/farmlab list` prints all coordinates. The builder deliberately does not bulk-register or profile farms, so the performance review still uses the production command contract. Keep players near the selected plots so that the spawners remain active, record the actual active subset, and use the same subset for original and stasis phases.

This world is a benchmark input, not a long-running automated test. Structural checks and compilation are sufficient when changing it; run the measurement protocol in [performance.md](../../docs/testing/performance.md) when an operator is ready to collect MSPT and production data.
