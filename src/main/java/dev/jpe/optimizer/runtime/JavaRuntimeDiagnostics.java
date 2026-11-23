package dev.jpe.optimizer.runtime;

import com.mojang.logging.LogUtils;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.module.ModuleFinder;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Reports the Java runtime capabilities that matter to JPE Optimizer.
 *
 * This is deliberately diagnostic-only: it does not change JVM flags, select a
 * collector, or make world-generation decisions based on an unverified feature.
 */
public final class JavaRuntimeDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();

    private JavaRuntimeDiagnostics() {}

    public static void logRuntimeCapabilities() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        CompilationMXBean compiler = ManagementFactory.getCompilationMXBean();

        String gcNames = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .toList()
                .toString();

        boolean vectorInImage = moduleInImage("jdk.incubator.vector");
        boolean vectorResolved = ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
        boolean jfrInImage = moduleInImage("jdk.jfr");
        boolean managementInImage = moduleInImage("jdk.management");

        LOGGER.info(
                "Java runtime: version={}, runtime={}, vm={} {}, vendor={}, arch={}, os={}/{}",
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.runtime.version", "unknown"),
                System.getProperty("java.vm.name", "unknown"),
                System.getProperty("java.vm.version", "unknown"),
                System.getProperty("java.vendor", "unknown"),
                System.getProperty("os.arch", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown")
        );
        LOGGER.info(
                "JVM capabilities: processors={}, maxHeapMiB={}, compiler={}, compilationTimeSupported={}, collectors={}",
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024L * 1024L),
                compiler == null ? "none" : compiler.getName(),
                compiler != null && compiler.isCompilationTimeMonitoringSupported(),
                gcNames
        );
        LOGGER.info(
                "Java 25 modules: vectorInImage={}, vectorResolved={}, jfrInImage={}, jdkManagementInImage={}",
                vectorInImage, vectorResolved, jfrInImage, managementInImage
        );

        if (vectorInImage && !vectorResolved) {
            LOGGER.info("Vector API is packaged but not resolved; add JVM argument --add-modules=jdk.incubator.vector to enable it.");
        } else if (vectorResolved) {
            logVectorSpecies();
        }

        logCompactObjectHeaders();
        LOGGER.info("JVM input arguments: {}", runtime.getInputArguments());
    }

    private static boolean moduleInImage(String moduleName) {
        try {
            return ModuleFinder.ofSystem().find(moduleName).isPresent();
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.debug("Could not inspect runtime module {}: {}", moduleName, exception.toString());
            return false;
        }
    }

    private static void logVectorSpecies() {
        try {
            Class<?> vectorClass = Class.forName("jdk.incubator.vector.FloatVector");
            Object species = vectorClass.getField("SPECIES_PREFERRED").get(null);
            Class<?> speciesType = Class.forName("jdk.incubator.vector.VectorSpecies");
            Object lanes = speciesType.getMethod("length").invoke(species);
            LOGGER.info("Vector API active: preferred FloatVector species has {} lanes", lanes);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            LOGGER.warn("Vector API module is resolved, but preferred species could not be queried: {}", exception.toString());
        }
    }

    private static void logCompactObjectHeaders() {
        try {
            Class<?> beanType = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object bean = ManagementFactory.class
                    .getMethod("getPlatformMXBean", Class.class)
                    .invoke(null, beanType);
            if (bean == null) {
                LOGGER.info("Compact object headers: HotSpot diagnostic bean unavailable");
                return;
            }
            Object option = beanType.getMethod("getVMOption", String.class)
                    .invoke(bean, "UseCompactObjectHeaders");
            String value = (String) option.getClass().getMethod("getValue").invoke(option);
            LOGGER.info("Compact object headers: UseCompactObjectHeaders={}", value);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            LOGGER.info("Compact object headers: VM option unavailable ({})", exception.getClass().getSimpleName());
        }
    }
}
