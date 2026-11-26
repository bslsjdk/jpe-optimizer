# Density Graph tile-size benchmark

## Measurement

Workflow: Native Perlin Verification, run 45754866566, commit 0c4b420fdf4b2b31e6101eef49e3cb9e82631a37.

- Volume: full Minecraft density lattice, 5 x 49 x 5 = 1,225 samples.
- Graph: synthetic 164-node arithmetic chain, chosen to match the captured vanilla Overworld graph node count (164). It is a scratch/dispatch workload, not a representative mix of all operation types.
- Method: same process, 21 rounds, 24 full-volume evaluations per tile size per round, rotating tile-size order each round, median per-call time, exact output compared against the scalar reference for every run.
- Native test result at this commit: 48 passed, 0 failed.

| Tile ceiling | Median per full chunk |
|---:|---:|
| 256 | 0.040936 ms |
| 384 | 0.040173 ms |
| 512 | 0.039291 ms |
| 768 | 0.038516 ms |
| 1024 | 0.040273 ms |
| 1225 | 0.040841 ms |

768 was fastest in this sweep: about 1.97% faster than 512 and 5.69% faster than 1225. A separate 9-node graph also favored 512 over 1225, reinforcing that the largest tile is not automatically fastest.

## Decision

Production ceiling set to 768 samples. The 1 MiB node-value scratch budget still lowers the actual tile size for larger graphs. For 164 nodes, the budget permits the tested sizes; for a 600-node graph, the budget limits the tile to roughly 436 samples.

The 164-node graph is synthetic and uses a linear ADD chain. This is not a whole-chunk world-generation benchmark and must not be translated into a world-generation speedup claim. Revisit the ceiling after benchmarking a captured real Overworld graph with its actual operation mix and opaque/input boundaries, then validate end-to-end chunk generation.
