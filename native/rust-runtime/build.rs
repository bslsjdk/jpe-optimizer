fn main() {
    println!("cargo:rerun-if-changed=../cpp-kernels/src/bulk_probe.cpp");
    println!("cargo:rerun-if-changed=../include/jpe_native_api.h");
    cc::Build::new()
        .cpp(true)
        .std("c++17")
        .warnings(true)
        .file("../cpp-kernels/src/bulk_probe.cpp")
        .include("../include")
        .define("JPE_NATIVE_BUILD", None)
        .compile("jpe_cpp_kernels");
}
