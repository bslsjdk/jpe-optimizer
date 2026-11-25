#include <immintrin.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

#if !defined(__x86_64__) && !defined(__i386__)
#error "This diagnostic requires x86 AVX2"
#endif

__attribute__((target("avx2")))
static void classify_avx2(const float* input, uint8_t* output, size_t count) {
    size_t i = 0;
    for (; i + 8 <= count; i += 8) {
        const __m256 values = _mm256_loadu_ps(input + i);
        const __m256 mask = _mm256_cmp_ps(values, _mm256_setzero_ps(), _CMP_LE_OQ);
        output[i / 8] = static_cast<uint8_t>(_mm256_movemask_ps(mask));
    }
    if (i < count) {
        uint8_t bits = 0;
        for (size_t lane = 0; i + lane < count; ++lane) {
            if (input[i + lane] <= 0.0F) bits |= static_cast<uint8_t>(1U << lane);
        }
        output[i / 8] = bits;
    }
}

static void classify_scalar(const float* input, uint8_t* output, size_t count) {
    size_t i = 0;
    for (; i + 8 <= count; i += 8) {
        uint8_t bits = 0;
        for (size_t lane = 0; lane < 8; ++lane) {
            if (input[i + lane] <= 0.0F) bits |= static_cast<uint8_t>(1U << lane);
        }
        output[i / 8] = bits;
    }
    if (i < count) {
        uint8_t bits = 0;
        for (size_t lane = 0; i + lane < count; ++lane) {
            if (input[i + lane] <= 0.0F) bits |= static_cast<uint8_t>(1U << lane);
        }
        output[i / 8] = bits;
    }
}

static uint64_t checksum(const std::vector<uint8_t>& v) {
    uint64_t h = 1469598103934665603ULL;
    for (uint8_t x : v) h = (h ^ x) * 1099511628211ULL;
    return h;
}

template<class Fn>
static double bench(Fn fn, const std::vector<float>& input, std::vector<uint8_t>& output,
                    size_t iterations) {
    const auto start = std::chrono::steady_clock::now();
    uint64_t sink = 0;
    for (size_t n = 0; n < iterations; ++n) {
        fn(input.data(), output.data(), input.size());
        sink ^= checksum(output);
    }
    const auto end = std::chrono::steady_clock::now();
    volatile uint64_t keep = sink;
    (void)keep;
    return std::chrono::duration<double, std::nano>(end - start).count() / iterations;
}

int main() {
    __builtin_cpu_init();
    if (!__builtin_cpu_supports("avx2")) {
        std::puts("AVX2_UNAVAILABLE");
        return 0;
    }

    const size_t sizes[] = {8, 16, 32, 64, 128, 256, 300, 301, 320, 384, 512, 640, 768, 1024, 1536, 2048, 3072, 4096, 16384};
    std::puts("JPE_DENSITY_BATCH_AVX2 threshold=0.0 comparison=scalar-vs-avx2");
    std::puts("batch samples scalar_ns avx2_ns speedup samples_per_ns parity");
    for (size_t count : sizes) {
        std::vector<float> input(count);
        std::vector<uint8_t> scalar((count + 7) / 8);
        std::vector<uint8_t> vector((count + 7) / 8);
        uint32_t state = 0xC001D00DU;
        for (size_t i = 0; i < count; ++i) {
            state ^= state << 13; state ^= state >> 17; state ^= state << 5;
            const float unit = static_cast<float>(state & 0x00FFFFFFU) / 8388608.0F - 1.0F;
            input[i] = unit * (0.25F + static_cast<float>(i % 17) * 0.03125F);
        }

        classify_scalar(input.data(), scalar.data(), count);
        classify_avx2(input.data(), vector.data(), count);
        const bool parity = scalar == vector;
        if (!parity) {
            std::printf("PARITY_FAIL batch=%zu\n", count);
            return 2;
        }

        const size_t targetSamples = 32U * 1024U * 1024U;
        const size_t iterations = std::max<size_t>(2000, targetSamples / count);
        const double scalarNs = bench(classify_scalar, input, scalar, iterations);
        const double avx2Ns = bench(classify_avx2, input, vector, iterations);
        const double speedup = scalarNs / avx2Ns;
        std::printf("%zu %.2f %.2f %.3fx %.2f %s checksum=%llu\n",
            count, scalarNs, avx2Ns, speedup, count / avx2Ns,
            parity ? "PASS" : "FAIL",
            static_cast<unsigned long long>(checksum(vector)));
    }
    return 0;
}
