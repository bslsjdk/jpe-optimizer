//! Chooses where a pipeline's block data comes from, and owns the switch that
//! decides whether terrain is generated here or handed in by the host.
//!
//! # The two paths
//!
//! * **host** (the default) -- the host generates columns and pushes them in
//!   through `submit_chunk`. This is the path that has always existed, and it
//!   costs a host-side allocation plus a 192 KiB copy per column.
//! * **native** -- the pipeline pulls from [`NativeTerrainSource`], which asks
//!   the C++ generator for the column in-process. No host round-trip, no copy.
//!
//! # Why a switch and not a hard switch-over
//!
//! Turning native generation on changes where block data comes from. If it were
//! the only path, a bug in it would be a bug in every world, with no way to
//! compare against the old behaviour on the same seed. Keeping both behind one
//! flag means the two can be run side by side and diffed, which is the only
//! honest way to claim they agree.
//!
//! The flag is read once, at construction, and never re-read: a pipeline whose
//! data source changed mid-session would mix columns from two generators.
//!
//! # Naming
//!
//! `jpe.nativeTerrain` rather than reusing `jpe.nativeFinalDensity`. The latter
//! already means something specific -- it swaps the *density sampler* under
//! vanilla worldgen -- and overloading it would make "native is on" ambiguous
//! between two quite different replacements.

use crate::pipeline::BlockSource;

/// Environment/system property that selects the terrain path.
///
/// `native` (case-insensitive) selects [`NativeTerrainSource`]; anything else,
/// including unset, keeps the host-driven path. Parsed the same way as the
/// Java-side flags so a launcher can pass one style of value to both.
pub const NATIVE_TERRAIN_PROPERTY: &str = "jpe.nativeTerrain";

/// Where a pipeline's blocks come from.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum TerrainPath {
    /// The host generates columns and pushes them in.
    Host,
    /// The pipeline generates columns itself through the C++ engine.
    Native,
}

impl TerrainPath {
    /// Parses the switch value. Only an explicit `native` opts in; every other
    /// value, including a typo, stays on the path that has always worked.
    ///
    /// Defaulting to the known-good path on a typo is deliberate: a mistyped
    /// flag should cost the user the old behaviour, not a broken world.
    pub fn parse(value: Option<&str>) -> Self {
        match value {
            Some(v) if v.trim().eq_ignore_ascii_case("native") => TerrainPath::Native,
            _ => TerrainPath::Host,
        }
    }

    /// Reads the switch from the environment.
    ///
    /// Checks `JPE_NATIVE_TERRAIN` first so a launcher can set it without
    /// needing a JVM system property, then `jpe.nativeTerrain`. Both are
    /// trimmed and compared case-insensitively.
    pub fn from_env() -> Self {
        let from_env_var = std::env::var("JPE_NATIVE_TERRAIN").ok();
        if from_env_var.is_some() {
            return TerrainPath::parse(from_env_var.as_deref());
        }
        TerrainPath::parse(std::env::var(NATIVE_TERRAIN_PROPERTY).ok().as_deref())
    }

    /// True when terrain is generated in-process.
    pub fn is_native(self) -> bool {
        matches!(self, TerrainPath::Native)
    }
}

/// A source that satisfies `BlockSource` whichever path was chosen.
///
/// The pipeline is generic over `BlockSource`, so something has to be one type
/// at the call site even when the decision is made at runtime. This enum is
/// that type: it forwards `section()` to whichever arm is live, so the pipeline
/// never learns which one it got.
///
/// The host arm holds no data of its own -- on that path the host pushes blocks
/// in through `submit_chunk`, and the pipeline's own cache serves them. It
/// therefore answers `section()` with an empty section rather than pretending
/// to have data it was never given.
#[cfg(feature = "cpp-bridge")]
pub enum TerrainSource {
    /// Host-pushed blocks; `section()` has nothing to answer with.
    Host,
    /// In-process generation through the C++ engine.
    Native(crate::native_terrain_source::NativeTerrainSource),
}

#[cfg(feature = "cpp-bridge")]
impl BlockSource for TerrainSource {
    fn section(&mut self, pos: crate::coords::SectionPos) -> crate::mesh::ChunkSection {
        match self {
            TerrainSource::Host => crate::mesh::ChunkSection::new(),
            TerrainSource::Native(source) => source.section(pos),
        }
    }
}

#[cfg(feature = "cpp-bridge")]
impl TerrainSource {
    /// Builds the source for `path`. Returns `Err` only when the native path was
    /// requested and the generator could not be created -- a failure the caller
    /// should surface rather than silently fall back from, because a silent
    /// fallback would leave the user believing native generation is on.
    pub fn for_path(
        path: TerrainPath,
        config: crate::terrain_gen::TerrainConfig,
    ) -> Result<Self, String> {
        match path {
            TerrainPath::Host => Ok(TerrainSource::Host),
            TerrainPath::Native => {
                let generator = crate::terrain_gen::NativeGenerator::new(config)
                    .map_err(|e| format!("native terrain generator: {}", e))?;
                let source = crate::native_terrain_source::NativeTerrainSource::new(generator, config)?;
                Ok(TerrainSource::Native(source))
            }
        }
    }

    /// The live path, for reporting and for tests that must not guess.
    pub fn path(&self) -> TerrainPath {
        match self {
            TerrainSource::Host => TerrainPath::Host,
            TerrainSource::Native(_) => TerrainPath::Native,
        }
    }

    /// Columns generated in-process. Zero on the host path, which is how a test
    /// proves the switch actually changed where the data came from.
    pub fn generations(&self) -> u64 {
        match self {
            TerrainSource::Host => 0,
            TerrainSource::Native(source) => source.generations,
        }
    }
}

#[cfg(all(test, feature = "cpp-bridge"))]
mod terrain_path_tests {
    use super::*;
    use crate::coords::SectionPos;
    use crate::terrain_gen::TerrainConfig;

    #[test]
    fn only_an_explicit_native_value_opts_in() {
        assert_eq!(TerrainPath::parse(Some("native")), TerrainPath::Native);
        assert_eq!(TerrainPath::parse(Some("  NATIVE  ")), TerrainPath::Native);
        assert_eq!(TerrainPath::parse(Some("Native")), TerrainPath::Native);
    }

    #[test]
    fn everything_else_stays_on_the_known_good_path() {
        // Unset, empty, a typo, and the other flags' vocabulary all mean host.
        // A mistyped switch must not produce a world that cannot be trusted.
        assert_eq!(TerrainPath::parse(None), TerrainPath::Host);
        assert_eq!(TerrainPath::parse(Some("")), TerrainPath::Host);
        assert_eq!(TerrainPath::parse(Some("off")), TerrainPath::Host);
        assert_eq!(TerrainPath::parse(Some("commit")), TerrainPath::Host);
        assert_eq!(TerrainPath::parse(Some("nativ")), TerrainPath::Host);
        assert_eq!(TerrainPath::parse(Some("true")), TerrainPath::Host);
    }

    #[test]
    fn the_host_arm_answers_with_an_empty_section() {
        // On the host path blocks arrive through submit_chunk, so a pull has
        // nothing to give. Returning empty is honest; inventing blocks here
        // would render terrain that disagrees with what the host submitted.
        let mut source = TerrainSource::for_path(TerrainPath::Host, TerrainConfig::default())
            .expect("host source");
        assert_eq!(source.path(), TerrainPath::Host);
        assert_eq!(source.generations(), 0);

        let section = source.section(SectionPos::new(0, -4, 0));
        assert!(section.is_empty(), "the host arm must not invent blocks");
    }

    #[test]
    fn the_native_arm_generates_and_says_so() {
        let config = TerrainConfig {
            seed: 5,
            ..TerrainConfig::default()
        };
        let mut source =
            TerrainSource::for_path(TerrainPath::Native, config).expect("native source");
        assert_eq!(source.path(), TerrainPath::Native);
        assert_eq!(source.generations(), 0, "construction must not generate");

        let _ = source.section(SectionPos::new(0, config.base_section_y(), 0));
        assert_eq!(
            source.generations(),
            1,
            "the native arm must have generated exactly one column"
        );
    }

    #[test]
    fn the_switch_changes_where_data_comes_from() {
        // The point of the flag: same seed, same section, different provenance.
        // A test that only checked "it did not crash" would pass even if the
        // flag were ignored.
        let config = TerrainConfig {
            seed: 9,
            ..TerrainConfig::default()
        };
        let pos = SectionPos::new(0, config.base_section_y(), 0);

        let mut host = TerrainSource::for_path(TerrainPath::Host, config).expect("host");
        let mut native = TerrainSource::for_path(TerrainPath::Native, config).expect("native");

        let from_host = host.section(pos);
        let from_native = native.section(pos);

        assert!(from_host.is_empty());
        assert_eq!(native.generations(), 1);
        assert!(
            !from_native.is_empty(),
            "the native arm must produce blocks for the bottom section"
        );
    }
}