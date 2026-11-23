package dev.jpe.optimizer.benchmark;

/**
 * Launches the Vector API probe without statically linking the incubator module.
 * That lets users get a useful diagnostic when the module exists in the runtime
 * image but has not been resolved with --add-modules.
 */
public final class VectorApiBenchmark {
    private static final String VECTOR_MODULE = "jdk.incubator.vector";
    private static final String KERNEL_CLASS = "dev.jpe.optimizer.benchmark.VectorApiKernel";

    private VectorApiBenchmark() {}

    public static void main(String[] args) throws ReflectiveOperationException {
        System.out.printf(
                "Runtime: java=%s, runtime=%s, vm=%s %s, arch=%s, os=%s/%s%n",
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.runtime.version", "unknown"),
                System.getProperty("java.vm.name", "unknown"),
                System.getProperty("java.vm.version", "unknown"),
                System.getProperty("os.arch", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown")
        );

        if (ModuleLayer.boot().findModule(VECTOR_MODULE).isEmpty()) {
            System.err.println("Vector API module is not resolved.");
            System.err.println("Add this as a separate JVM argument: --add-modules=jdk.incubator.vector");
            throw new IllegalStateException("Cannot run Vector API probe because " + VECTOR_MODULE + " is not resolved");
        }

        Class<?> kernelClass = Class.forName(KERNEL_CLASS);
        try {
            kernelClass.getMethod("run", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Vector API benchmark failed", cause);
        }
        DensityKernelProbe.run(args);
    }
}
