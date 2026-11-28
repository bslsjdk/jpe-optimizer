fn main() {
    println!("cargo:rerun-if-env-changed=PROFILE");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/bulk_probe.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/perlin_volume.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/perlin_stack.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/density_graph.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/blender.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/aquifer_random.cpp");
    println!("cargo:rerun-if-changed=../include/jpe_native_api.h");
    let mut build = cc::Build::new();
    build.cpp(true)
        .std("c++17")
        .flag_if_supported("-ffp-contract=off")
        .flag_if_supported("-fno-fast-math")
        .warnings(true)
        .file("../cpp-kernels/src/bulk_probe.cpp")
        .file("../cpp-kernels/src/perlin_volume.cpp")
        .file("../cpp-kernels/src/perlin_stack.cpp")
        .file("../cpp-kernels/src/density_graph.cpp")
        .file("../cpp-kernels/src/blender.cpp")
        .file("../cpp-kernels/src/aquifer_random.cpp")
        .include("../include")
        .define("JPE_NATIVE_BUILD", None);
    // The scalar comparison helper is present only in explicit benchmark builds,
    // never in the normal release runtime artifact.
    if std::env::var_os("CARGO_FEATURE_DENSITY_GRAPH_BENCHMARK").is_some() {
        build.define("JPE_DENSITY_GRAPH_TEST_REFERENCE", None);
    }
    build.compile("jpe_cpp_kernels");

    // ---------------------------------------------------------------------
    // The world generator itself.
    //
    // Kept in a separate cc::Build from the kernels above for one reason:
    // these four translation units are the whole terrain engine, and they are
    // also compiled standalone by the `native terrain` workflow. Two build
    // systems compiling the same files from the same flags is how a
    // "works in Rust, fails in CI" divergence starts, so the flags below are
    // deliberately identical to the ones in .github/workflows/native-terrain.yml.
    //
    // -ffp-contract=off and -fno-fast-math are not stylistic: the generator
    // promises bit-identical output against Minecraft's float32 reference, and
    // contraction changes rounding. Dropping them would still compile and still
    // produce terrain that looks right, which is the worst kind of difference.
    // ---------------------------------------------------------------------
    for f in [
        "../cpp-world/src/generator.cpp",
        "../cpp-world/src/terrain_api.cpp",
        "../cpp-world/src/chunk_cache.cpp",
        "../cpp-world/src/terrain_stream_api.cpp",
    ] {
        println!("cargo:rerun-if-changed={}", f);
    }
    println!("cargo:rerun-if-changed=../cpp-world/include/jpe_terrain_api.h");
    println!("cargo:rerun-if-changed=../cpp-world/include/jpe_terrain_stream_api.h");
    println!("cargo:rerun-if-changed=../cpp-world/src/chunk_cache.h");
    println!("cargo:rerun-if-changed=../cpp-world/src/jpe_terrain_internal.h");

    let mut terrain = cc::Build::new();
    terrain
        .cpp(true)
        .std("c++20")
        // -O2 matches the CI build. A debug runtime still gets the cache
        // tests at a usable speed, and an unoptimised generator is slow
        // enough to look like a design problem.
        .opt_level(2)
        .flag_if_supported("-ffp-contract=off")
        .flag_if_supported("-fno-fast-math")
        .flag_if_supported("-fno-strict-aliasing")
        // Not -Werror here: a warning in the generator should not stop a Rust
        // build, and CI already treats warnings as errors where it matters.
        .warnings(true)
        .file("../cpp-world/src/generator.cpp")
        .file("../cpp-world/src/terrain_api.cpp")
        .file("../cpp-world/src/chunk_cache.cpp")
        .file("../cpp-world/src/terrain_stream_api.cpp")
        .include("../cpp-world/include")
        .include("../cpp-world/src");
    terrain.compile("jpe_cpp_terrain");
}
