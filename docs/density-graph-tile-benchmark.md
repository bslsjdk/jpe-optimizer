# Density Graph tile-size benchmark

## Current measurement

Workflow: Native Perlin Verification, run 45755577498, commit 9dc4a62058ce4046f790fe7ad3429bb70418e3bf.

- Volume: full Minecraft density lattice, 5 x 49 x 5 = 1,225 samples.
- Graph: 164 nodes matching the captured vanilla Overworld inventory: 11 constants, 60 arithmetic instructions (ADD, MUL, zero-MUL, MIN, MAX, CLAMP), and 93 cache/context/opaque boundaries represented as opaque nodes.
- Method: same process, 51 rounds, 24 full-volume evaluations per tile size per round, rotating tile-size order each round, median per-call time. Tiled outputs are compared bit-for-bit against the scalar reference, including NaN payloads.
- Native test result: 48 passed, 0 failed.

| Tile ceiling | Median per full chunk |
|---:|---:|
| 256 | 0.021647 ms |
| 384 | 0.021732 ms |
| 512 | 0.023760 ms |
| 768 | 0.023144 ms |
| 1024 | 0.024505 ms |
| 1225 | 0.026160 ms |

256 was fastest in this operation-mix sweep. 384 was within about 0.4%; 512 and larger were slower. The earlier 164-node linear-ADD-chain sweep favored larger tiles, demonstrating why node count alone is not a representative workload.

## Decision

Production ceiling set to 256 samples, pending another run after the change. The 1 MiB node-value scratch budget remains an independent upper bound and automatically reduces the actual tile size for larger graphs. The 600-node full-chunk test explicitly requests 1,225 samples in benchmark builds, forcing the budget to cap the tile at 436 samples.

This is a full-chunk density-lattice microbenchmark, not a whole-chunk world-generation benchmark. The graph operation mix is reconstructed from the captured inventory; cache/context/opaque boundaries are represented by opaque nodes, so this is still not a captured executable graph. Do not translate these numbers into a world-generation speedup claim. Next performance validation should use a captured real Overworld graph with actual sampler boundaries and then measure end-to-end chunk generation.
