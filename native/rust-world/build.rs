// Build script for the Rust world engine.
//
// With `--features cpp-bridge` the native terrain generator in
// `native/cpp-world/` is compiled into this crate, so `libjpe_world.so` carries
// the generator itself and the standalone harness/example needs no second
// shared object. Without the feature this script does nothing and the crate
// stays pure Rust with zero C++ and zero build-time deps beyond `cc` itself.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    if std::env::var_os("CARGO_FEATURE_CPP_BRIDGE").is_none() {
        return;
    }

    let world = "../cpp-world";
    for f in [
        "src/generator.cpp",
        "src/terrain_api.cpp",
        "src/jpe_terrain_internal.h",
        "include/jpe_terrain_api.h",
    ] {
        println!("cargo:rerun-if-changed={world}/{f}");
    }

    let mut build = cc::Build::new();
    build
        .cpp(true)
        .std("c++17")
        // Determinism first: no FMA contraction, no fast-math. The generator's
        // output must not depend on the compiler's freedom to reassociate.
        .flag_if_supported("-ffp-contract=off")
        .flag_if_supported("-fno-fast-math")
        .flag_if_supported("-O3")
        .warnings(true)
        .file(format!("{world}/src/generator.cpp"))
        .file(format!("{world}/src/terrain_api.cpp"))
        .include(format!("{world}/include"))
        .include(format!("{world}/src"))
        .define("JPE_NATIVE_BUILD", None);
    build.compile("jpe_terrain_cpp");
}
