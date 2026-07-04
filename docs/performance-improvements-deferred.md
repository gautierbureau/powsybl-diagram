# Performance: full-network NAD benchmark and deferred improvements

This document summarizes the performance deep dive done on branch
`claude/performance-improvements-deep-dive-jtl285`:

- a benchmark of the **full-depth network area diagram on the MATPOWER `case13659pegase` case**
  (13,659 buses / 20,467 branches — imported as 8,354 voltage levels, 14,738 lines, 5,729 2WT),
- the improvements that were implemented as a result,
- and the list of improvement opportunities that are **deferred for now**, with the reason each one
  was not done.

All implemented changes preserve floating-point operation order and iteration order, so layouts and
SVG outputs are bit-identical to before (verified: the benchmark SVG is byte-for-byte identical
before/after, and all reference SVG tests pass unchanged).

## Benchmark

Setup: `NetworkAreaDiagram.draw`-equivalent pipeline with per-phase timers, default `NadParameters`
(Atlas2 force layout + overlap-prevention post-processing, topological style provider, straight edge
routing), whole network (`VoltageLevelFilter.NO_FILTER`), OpenJDK 21, `-Xmx6g`, JFR profiling
enabled. The layout operates on ~16.7k points (8,354 voltage level nodes + their text nodes).

Phase times, before / after the optimizations of this branch (best of 2 iterations):

| Phase                          | Before (ms) | After (ms) |
|--------------------------------|------------:|-----------:|
| style provider init            |           5 |          7 |
| graph building                 |         299 |        316 |
| layout — Atlas2 main loop      |     ~56,700 |    ~52,500 |
| layout — overlap post-processing |   ~90,600 |    ~23,900 |
| style application              |         111 |        111 |
| routing + SVG writing (15 MB)  |         720 |        731 |
| metadata writing (11.5 MB)     |         146 |        167 |
| **TOTAL**                      | **~148,600**| **~77,800**|

With **deferred items 1 and 2 also applied** (this combined branch — spatial grid for the overlap
repulsion and squared Barnes-Hut criterion), the same 13k case drops further to **~53–55 s total**
(Atlas2 main loop ~49 s, overlap post-processing ~3.2 s) — about **2.7× faster than the session-start
baseline**, with the same convergence (2,864 layout steps) and visual quality. The output SVG drifts
marginally from the bit-identical branch, as expected from the two approximation changes on a large
graph.

Key facts:

- **The layout is ~97% of the total.** Everything else — graph building, routing, writing a 15 MB
  SVG and an 11.5 MB metadata JSON — costs about 2 s combined at this scale.
- The Atlas2 main loop (Barnes-Hut, 2,864 steps until its stop condition) costs ~52–57 s. The JFR
  profile shows the time is dominated by the quadtree traversal itself
  (`RepulsionForceDegreeBasedLinearBarnesHut.applyRepulsionFromNode`), which is the expected,
  inherent cost of the algorithm.
- The **overlap-prevention post-processing** was the single largest cost (~90 s, ~50% of all
  profile samples): it runs a hard-coded **90 iterations of all-pairs O(n²) repulsion, with no
  Barnes-Hut approximation, no timeout and no convergence-based early exit**
  (`OverlapPreventionPostProcessing` / `RepulsionForceDegreeBasedNoOverlapLinear`). The constant
  factor was reduced ~3.8× on this branch (90 s → 24 s) by removing the per-pair allocations and by
  iterating a point-array snapshot instead of the points `HashMap` in the O(n²) inner loop — but the
  quadratic asymptotic behavior remains (see deferred item 1).
- ~17% of the "before" profile samples were `HashMap` operations inside the layout loops (points map
  iteration in the O(n²) loop, Atlas2 per-point force/swing bookkeeping maps). These were removed by
  the array snapshot and by replacing the Atlas2 bookkeeping maps with arrays aligned on the moving
  points order.

## Deferred items

Candidates for future work, roughly ordered by expected impact.

### 1. Overlap-prevention post-processing is O(90 · n²) — needs an asymptotic fix (diagram-util)

Measured on the 13k case: originally ~90 s, still ~24 s after the constant-factor work on this
branch, for ~16.7k points; it grows quadratically with the diagram size. Options, by increasing
ambition:

- reuse the Barnes-Hut quadtree for the no-overlap repulsion (the force only acts within
  `repulsionZoneRadius`, so a spatial index would skip almost all pairs; note the zone radius
  `10 × (0.008·n + 4)` itself grows with n, which is questionable at large sizes);
- add a convergence criterion or a time budget instead of the hard-coded 90 iterations;
- make the post-processing opt-out via `NadParameters` for very large diagrams.

**Why deferred:** any of these changes the resulting layout, so all reference SVGs would need to be
regenerated, and parameter/API decisions belong to the maintainers. This is the top candidate for a
follow-up: it is the dominant scalability limit of the default full-network NAD.

**Experiment branch: `claude/nad-overlap-prevention-spatial-grid`** — implements the spatial-index
option with a uniform hash grid (cell size = interaction radius + displacement-clamp margin, rebuilt
each iteration; the force formula is unchanged, only the pair enumeration order changes). Measured:
post-processing 24 s → **3.1 s** on the 13k case (total full-network NAD ~55 s); **all 182 existing
reference tests pass unchanged** (on the small test graphs the interacting pairs land in a single
cell or there are none, so the computation is bit-identical there); the 13k SVG drifts marginally
from the parent branch as expected from the changed floating-point summation order.

### 2. Barnes-Hut acceptance criterion without square root (diagram-util)

`applyRepulsionFromNode` evaluates `nodeWidth < θ · point.distanceTo(barycenter)` — one `Math.sqrt`
per visited quadtree node per point per step, in what is now the hottest remaining loop (~27% of
samples). Comparing squared values removes the sqrt.

**Why deferred:** mathematically equivalent but not bit-identical; a borderline accept/reject
decision can flip and change the layout, breaking the reference SVGs. Do it together with a
deliberate reference regeneration (e.g. at the same time as item 1).

**Experiment branch: `claude/nad-barnes-hut-squared-criterion`** — implements the squared
comparison (θ² precomputed, squared node width propagated through the recursion, delta vector and
squared distance reused by the repulsion computation). Measured: Atlas2 main loop 52–57 s →
**48–49 s** (~10%) on the 13k case, same step count; **all 182 existing reference tests pass
unchanged** (no acceptance decision flips on any test graph), but the 13k SVG differs slightly from
the parent branch, confirming borderline flips do occur on large graphs.

### 3. Atlas2 `EdgeAttractionForceLinear`: per-neighbor point lookup (diagram-util)

After the other fixes, the remaining `HashMap.getNode` samples (~5%) come mostly from
`layoutContext.getAllPoints().get(otherVertex)` per neighbor per step, plus the per-call
`edgesOf(vertex)` unmodifiable-set wrapper. Precomputing, at `init`, the neighbor `Point[]` for each
vertex (or vertex-indexed adjacency arrays) would remove the map lookups from the main loop.

**Why deferred:** moderate gain (a few % of layout time); requires a per-force adjacency snapshot
structure. Straightforward but was out of time-box; safe to do bit-identically if the neighbor order
of `edgesOf` iteration is preserved.

### 4. `Point.resetForces()` allocation (diagram-util)

Allocates a fresh `Vector2D` per point per step (~137 profile samples). It cannot simply zero the
vector in place: `Atlas2ForceLayoutAlgorithm.updateAllPositions` stores the current force object as
the "previous force" right before the reset and relies on the reset allocating a new object.

**Why deferred:** fixing it requires reworking the previous-force bookkeeping to copy values instead
of aliasing the object (now easier since the bookkeeping is array-based, but still an aliasing-prone
change for a small gain).

### 5. `VoltageLevelFilter.getNextDepthVoltageLevels` second topology traversal (network-area-diagram)

Rebuilds a depth-1 `VoltageLevelFilter` over all visible voltage levels, re-running the equipment
traversal across the whole visible set — a second full topology walk on top of the one that
established the visible set — plus an intermediate id list re-resolved through
`network.getVoltageLevel(id)`.

**Why deferred:** restructuring touches the filter semantics (which voltage levels are shown as
"next depth"); measured graph-building cost is ~0.3 s at 13k buses, so the priority is low.

### 6. `Subsection` shunt-cell arrangement `indexOf` patterns (single-line-diagram)

`Subsection.java` (~lines 288–307) performs several `List.indexOf` scans on `externCells` and
`subsections` inside nested loops over subsections and shunt cells.

**Why deferred:** runs once per layout on small collections; convoluted rewrite for negligible gain.

### 7. SLD `ZoneGraph.getVoltageLevels()` rebuilds the list on every call (single-line-diagram)

Re-streams all substations and collects a fresh list on each call. The snake-line call sites were
fixed to cache the result on this branch; other call sites still pay the rebuild.

**Why deferred:** remaining call sites are not in hot loops; caching inside `ZoneGraph` would need
invalidation when substations are added.

### 8. `DiagramMetadata` streaming (network-area-diagram)

The metadata writer builds full intermediate metadata lists before serializing with Jackson,
doubling transient memory of the metadata phase (11.5 MB JSON on the 13k case).

**Why deferred:** measured at ~0.15 s; streaming generation complicates the code for a modest gain.

### 9. Maintenance note: typed collections in NAD `Graph`

`voltageLevelNodes`, `threeWtNodes` and `threeWtEdges` are now maintained at insertion.
`Graph` has no removal API today; if one is ever added, these collections must be kept in sync.

## Reproducing the benchmark

1. Download `case13659pegase.m` from the MATPOWER repository and convert it to a `.mat` file
   containing the `mpc` struct (`version`, `baseMVA`, `bus`, `gen`, `branch`) — e.g. with
   `scipy.io.savemat`.
2. Import it with `powsybl-matpower-converter` (`Network.read(path)`).
3. Run the `NetworkAreaDiagram.draw` pipeline with default `NadParameters` and
   `VoltageLevelFilter.NO_FILTER`, timing each phase separately (graph build, layout, style
   application, routing + SVG writing, metadata writing).
