fn main() {
    println!("cargo:rerun-if-changed=../cpp-kernels/src/bulk_probe.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/perlin_volume.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/perlin_stack.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/density_graph.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/aquifer_core.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/aquifer_random.cpp");
    println!("cargo:rerun-if-changed=../cpp-kernels/src/prepared_perlin_stack.cpp");
    println!("cargo:rerun-if-changed=../include/jpe_native_api.h");
    cc::Build::new()
        .cpp(true)
        .std("c++17")
        .flag_if_supported("-ffp-contract=off")
        .flag_if_supported("-fno-fast-math")
        .warnings(true)
        .file("../cpp-kernels/src/bulk_probe.cpp")
        .file("../cpp-kernels/src/perlin_volume.cpp")
        .file("../cpp-kernels/src/perlin_stack.cpp")
        .file("../cpp-kernels/src/density_graph.cpp")
        .file("../cpp-kernels/src/aquifer_core.cpp")
        .file("../cpp-kernels/src/aquifer_random.cpp")
        .file("../cpp-kernels/src/prepared_perlin_stack.cpp")
        .include("../include")
        .define("JPE_NATIVE_BUILD", None)
        .compile("jpe_cpp_kernels");
}
