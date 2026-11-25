fn main() {
    println!("cargo:rerun-if-env-changed=PROFILE");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/bulk_probe.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/perlin_volume.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/perlin_stack.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/density_graph.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/blender.cpp");
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
        .include("../include")
        .define("JPE_NATIVE_BUILD", None);
    // The scalar comparison helper is present only in explicit benchmark builds,
    // never in the normal release runtime artifact.
    if std::env::var_os("CARGO_FEATURE_DENSITY_GRAPH_BENCHMARK").is_some() {
        build.define("JPE_DENSITY_GRAPH_TEST_REFERENCE", None);
    }
    build.compile("jpe_cpp_kernels");
}
