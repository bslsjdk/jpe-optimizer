#include "jpe_native_api.h"

#include <cstdint>
#include <cstring>

namespace {

constexpr uint64_t kMask = UINT64_MAX;
constexpr uint64_t kXoroshiroSeedLoFallback = 0x9e3779b97f4a7c15ULL;
constexpr uint64_t kXoroshiroSeedHiFallback = 0x6a09e667f3bcc909ULL;

uint64_t rotate_left(uint64_t value, unsigned amount) noexcept {
    return (value << amount) | (value >> (64U - amount));
}

// Mirrors Mth.getSeed(int,int,int), including the int32 overflow in x*3129871
// before widening, followed by Java long overflow and arithmetic >> 16.
uint64_t minecraft_position_seed(int32_t x, int32_t y, int32_t z) noexcept {
    const uint32_t x_product_bits = static_cast<uint32_t>(x) * 3129871U;
    int32_t x_product = 0;
    std::memcpy(&x_product, &x_product_bits, sizeof(x_product));

    uint64_t value =
        static_cast<uint64_t>(static_cast<int64_t>(x_product)) ^
        (static_cast<uint64_t>(static_cast<int64_t>(z)) * 116129781ULL) ^
        static_cast<uint64_t>(static_cast<int64_t>(y));

    value = value * value * 42317861ULL + value * 11ULL;
    uint64_t shifted = value >> 16U;
    if ((value & (1ULL << 63U)) != 0) {
        shifted |= 0xffff000000000000ULL;
    }
    return shifted;
}

class Xoroshiro128PlusPlus {
public:
    Xoroshiro128PlusPlus(uint64_t seed_lo, uint64_t seed_hi) noexcept
        : seed_lo_(seed_lo), seed_hi_(seed_hi) {
        if ((seed_lo_ | seed_hi_) == 0) {
            seed_lo_ = kXoroshiroSeedLoFallback;
            seed_hi_ = kXoroshiroSeedHiFallback;
        }
    }

    uint64_t next_long() noexcept {
        const uint64_t s0 = seed_lo_;
        uint64_t s1 = seed_hi_;
        const uint64_t result = rotate_left(s0 + s1, 17U) + s0;
        s1 ^= s0;
        seed_lo_ = rotate_left(s0, 49U) ^ s1 ^ (s1 << 21U);
        seed_hi_ = rotate_left(s1, 28U);
        return result;
    }

    uint32_t next_int_bound(uint32_t bound) noexcept {
        const uint32_t rejection_threshold = (uint32_t{0} - bound) % bound;
        for (;;) {
            const uint32_t random_bits = static_cast<uint32_t>(next_long());
            const uint64_t product = static_cast<uint64_t>(random_bits) * bound;
            const uint32_t fractional_part = static_cast<uint32_t>(product);
            if (fractional_part >= rejection_threshold) {
                return static_cast<uint32_t>(product >> 32U);
            }
        }
    }

private:
    uint64_t seed_lo_;
    uint64_t seed_hi_;
};

} // namespace

extern "C" int32_t jpe_cpp_aquifer_xoroshiro_offsets(
    uint64_t seed_lo, uint64_t seed_hi,
    int32_t grid_x, int32_t grid_y, int32_t grid_z,
    int32_t *offset_x, int32_t *offset_y, int32_t *offset_z) noexcept {
    if (offset_x == nullptr || offset_y == nullptr || offset_z == nullptr) {
        return -1;
    }

    const uint64_t positional_seed = minecraft_position_seed(grid_x, grid_y, grid_z);
    Xoroshiro128PlusPlus random(positional_seed ^ seed_lo, seed_hi);

    *offset_x = static_cast<int32_t>(random.next_int_bound(10U));
    *offset_y = static_cast<int32_t>(random.next_int_bound(9U));
    *offset_z = static_cast<int32_t>(random.next_int_bound(10U));
    return 0;
}
