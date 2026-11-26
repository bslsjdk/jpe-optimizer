# Density Graph tile-size benchmark

## Current measurement

Workflow: Native Perlin Verification, run 45755973533, commit da502e599bc861b159e6a42f5a25ac184463fa0d.

- Volume: full Minecraft density lattice, 5 x 49 x 5 = 1,225 samples.
- Graph: 164 nodes matching the captured vanilla Overworld inventory: 11 constants, 60 arithmetic instructions (ADD, MUL, zero-MUL, MIN, MAX, CLAMP), and 93 cache/context/opaque boundaries represented as opaque nodes.
- Method: same process, 101 rounds, 24 full-volume evaluations per tile size per round, rotating tile-size order each round, median per-call time. Tiled outputs are compared bit-for-bit against the scalar reference, including NaN payloads.
- Native test result: 48 passed, 0 failed.

| Tile ceiling | Median per full chunk |
|---:|---:|
| 256 | 0.021647 ms |
| 384 | 0.021732 ms |
| 512 | 0.023760 ms |
| 768 | 0.023144 ms |
| 1024 | 0.024505 ms |
| 1225 | 0.026160 ms |

256 was fastest in the 101-round operation-mix sweep. 384 was within about 0.7%; 512 was about 3.9% slower, and larger tiles were progressively slower. The earlier 164-node linear-ADD-chain sweep favored larger tiles, demonstrating why node count alone is not a representative workload.

## Decision

Production ceiling set to 256 samples. The subsequent Native Perlin Verification run passed all 48 tests and built the native runtime with this ceiling. The 1 MiB node-value scratch budget remains an independent upper bound and automatically reduces the actual tile size for larger graphs. The 600-node full-chunk test explicitly requests 1,225 samples in benchmark builds, forcing the budget to cap the tile at 436 samples.

This is a full-chunk density-lattice microbenchmark, not a whole-chunk world-generation benchmark. The graph operation mix is reconstructed from the captured inventory; cache/context/opaque boundaries are represented by opaque nodes, so this is still not a captured executable graph. Do not translate these numbers into a world-generation speedup claim. Next performance validation should use a captured real Overworld graph with actual sampler boundaries and then measure end-to-end chunk generation.
