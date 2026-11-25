fn main() {
    println!("cargo:rerun-if-env-changed=PROFILE");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/bulk_probe.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/perlin_volume.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/perlin_stack.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/density_graph.cpp");
    println!("cargo:rerun-if-changed=../include/jpe_native_api.h");
    let mut build = cc::Build::new()
        .cpp(true)
        .std("c++17")
        .flag_if_supported("-ffp-contract=off")
        .flag_if_supported("-fno-fast-math")
        .warnings(true)
        .file("../cpp-kernels/src/bulk_probe.cpp")
        .file("../cpp-kernels/src/perlin_volume.cpp")
        .file("../cpp-kernels/src/perlin_stack.cpp")
        .file("../cpp-kernels/src/density_graph.cpp")
        .include("../include")
        .define("JPE_NATIVE_BUILD", None);
    // Keep the scalar comparison helper out of release runtime artifacts.
    if std::env::var("PROFILE").as_deref() == Ok("debug") {
        build.define("JPE_DENSITY_GRAPH_TEST_REFERENCE", None);
    }
    build.compile("jpe_cpp_kernels");
}
