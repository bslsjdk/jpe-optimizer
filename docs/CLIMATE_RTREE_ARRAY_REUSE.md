# Climate R-tree target-array reuse experiment

This branch tests one narrowly scoped allocation optimization for Minecraft 26.3.

## Hypothesis

Vanilla Climate.RTree.search calls TargetPoint.toParameterArray() once per biome lookup. That method creates a new seven-element long array. The tree traversal consumes the array synchronously and does not retain it after the search returns.

The experimental mixin redirects only that conversion. With -Djpe.climateTargetArrayReuse=true, it fills a seven-element thread-local scratch array instead. The default remains disabled; when disabled, the helper allocates a fresh array as vanilla does.

## Safety constraints

- The scratch array is thread-local, never shared between concurrent worldgen workers.
- The search entry/return hooks track nesting depth.
- A reentrant lookup uses a fresh array rather than overwriting the outer search's coordinates.
- The final seventh element remains zero, matching vanilla.
- The experiment does not change tree construction, child ordering, distance calculations, or biome tie-breaking.
- The optimization is not enabled by default.

## Validation

The CI workflow runs four fresh normal worlds with the same seed in off/on/on/off order. It requires the helper's enabled-state marker in each log and checks the continuous worldgen benchmark's normal completion conditions. Unit tests cover all six coordinates, the zero offset, scratch reuse, and the nested/disabled fallback.

A speedup is accepted only if the four-server ABBA results show a repeatable throughput or allocation improvement without correctness failures. Otherwise the experiment stays disabled or is discarded.