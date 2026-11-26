#include "jpe_native_api.h"

#include <cstdint>
#include <cstring>
#include <climits>
#include <unordered_map>

namespace {

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

class LegacyRandomSource {
public:
    explicit LegacyRandomSource(uint64_t seed) noexcept
        : state_((seed ^ 25214903917ULL) & ((1ULL << 48U) - 1ULL)) {}

    uint32_t next_bits(unsigned bits) noexcept {
        state_ = (state_ * 25214903917ULL + 11ULL) & ((1ULL << 48U) - 1ULL);
        return static_cast<uint32_t>(state_ >> (48U - bits));
    }

    uint32_t next_int_bound(uint32_t bound) noexcept {
        if ((bound & (bound - 1U)) == 0U) {
            return static_cast<uint32_t>(
                (static_cast<uint64_t>(bound) * next_bits(31U)) >> 31U);
        }
        for (;;) {
            const uint32_t sample = next_bits(31U);
            const uint32_t modulo = sample % bound;
            // Java int32 expression: sample - modulo + (bound - 1) < 0.
            const uint32_t signed_check = sample - modulo + (bound - 1U);
            if ((signed_check & 0x80000000U) == 0U) {
                return modulo;
            }
        }
    }

private:
    uint64_t state_;
};

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
    int32_t *offset_x, int32_t *offset_y, int32_t *offset_z) {
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

extern "C" int32_t jpe_cpp_aquifer_legacy_offsets(
    uint64_t factory_seed,
    int32_t grid_x, int32_t grid_y, int32_t grid_z,
    int32_t *offset_x, int32_t *offset_y, int32_t *offset_z) {
    if (offset_x == nullptr || offset_y == nullptr || offset_z == nullptr) {
        return -1;
    }

    const uint64_t positional_seed = minecraft_position_seed(grid_x, grid_y, grid_z);
    LegacyRandomSource random(positional_seed ^ factory_seed);
    *offset_x = static_cast<int32_t>(random.next_int_bound(10U));
    *offset_y = static_cast<int32_t>(random.next_int_bound(9U));
    *offset_z = static_cast<int32_t>(random.next_int_bound(10U));
    return 0;
}

namespace {
struct AquiferGridKey {
    int32_t x, y, z;
    bool operator==(const AquiferGridKey& o) const noexcept { return x==o.x && y==o.y && z==o.z; }
};
struct AquiferGridKeyHash {
    size_t operator()(const AquiferGridKey& k) const noexcept {
        uint64_t h=static_cast<uint32_t>(k.x);
        h=(h^static_cast<uint32_t>(k.y))*0x9e3779b185ebca87ULL;
        h=(h^static_cast<uint32_t>(k.z))*0xc2b2ae3d27d4eb4fULL;
        return static_cast<size_t>(h^(h>>32U));
    }
};
struct AquiferCenter { int32_t x,y,z; };
int32_t wrap_i32(uint32_t bits) noexcept { int32_t v; std::memcpy(&v,&bits,sizeof(v)); return v; }
int32_t add_i32(int32_t a,int32_t b) noexcept { return wrap_i32(static_cast<uint32_t>(a)+static_cast<uint32_t>(b)); }
int32_t mul_i32(int32_t a,int32_t b) noexcept { return wrap_i32(static_cast<uint32_t>(a)*static_cast<uint32_t>(b)); }
int32_t floor_div_i32(int32_t a,int32_t b) noexcept {
    int32_t q=a/b, r=a%b; if(r!=0 && ((r<0)!=(b<0))) --q; return q;
}
int32_t squared_distance_java(const AquiferCenter& c,int32_t x,int32_t y,int32_t z) noexcept {
    const int32_t dx=add_i32(c.x,wrap_i32(0U-static_cast<uint32_t>(x)));
    const int32_t dy=add_i32(c.y,wrap_i32(0U-static_cast<uint32_t>(y)));
    const int32_t dz=add_i32(c.z,wrap_i32(0U-static_cast<uint32_t>(z)));
    return add_i32(add_i32(mul_i32(dx,dx),mul_i32(dy,dy)),mul_i32(dz,dz));
}
}

extern "C" int32_t jpe_cpp_aquifer_nearest_four(
    uint32_t random_mode,uint64_t seed_lo,uint64_t seed_hi,
    const int32_t* block_x,const int32_t* block_y,const int32_t* block_z,
    uint32_t sample_count,int32_t* center_x,int32_t* center_y,int32_t* center_z,
    int32_t* squared_distances) try {
    constexpr uint32_t kMaxSamples=1024U*1024U;
    if(random_mode>1U || sample_count==0U || sample_count>kMaxSamples
       || !block_x || !block_y || !block_z || !center_x || !center_y || !center_z || !squared_distances) return 1;

    // Adjacent samples share most candidate cells. Cache each exact grid center once per volume.
    std::unordered_map<AquiferGridKey,AquiferCenter,AquiferGridKeyHash> cache;
    cache.reserve(static_cast<size_t>(sample_count)/4U+16U);
    for(uint32_t sample=0;sample<sample_count;++sample) {
        const int32_t x=block_x[sample], y=block_y[sample], z=block_z[sample];
        const AquiferGridKey anchor{
            floor_div_i32(add_i32(x,-5),16),
            add_i32(floor_div_i32(add_i32(y,1),12),-1),
            floor_div_i32(add_i32(z,-5),16)
        };
        int32_t best_d[4]={INT32_MAX,INT32_MAX,INT32_MAX,INT32_MAX};
        AquiferCenter best_c[4]{};
        // Exact vanilla candidate order: x=0..1, y=-1..1, z=0..1.
        for(int32_t ox=0;ox<=1;++ox) for(int32_t oy=-1;oy<=1;++oy) for(int32_t oz=0;oz<=1;++oz) {
            const AquiferGridKey key{add_i32(anchor.x,ox),add_i32(anchor.y,oy),add_i32(anchor.z,oz)};
            auto it=cache.find(key);
            if(it==cache.end()) {
                int32_t rx=0,ry=0,rz=0;
                const int32_t status=random_mode==0U
                    ? jpe_cpp_aquifer_xoroshiro_offsets(seed_lo,seed_hi,key.x,key.y,key.z,&rx,&ry,&rz)
                    : jpe_cpp_aquifer_legacy_offsets(seed_lo,key.x,key.y,key.z,&rx,&ry,&rz);
                if(status!=0) return 2;
                const AquiferCenter c{
                    add_i32(mul_i32(key.x,16),rx),
                    add_i32(mul_i32(key.y,12),ry),
                    add_i32(mul_i32(key.z,16),rz)
                };
                it=cache.emplace(key,c).first;
            }
            const int32_t d=squared_distance_java(it->second,x,y,z);
            // Vanilla >= insertion: later equal-distance candidates move ahead of earlier ones.
            uint32_t at=4U;
            for(uint32_t i=0;i<4U;++i) if(d<=best_d[i]) { at=i; break; }
            if(at<4U) {
                for(uint32_t i=3U;i>at;--i) { best_d[i]=best_d[i-1U]; best_c[i]=best_c[i-1U]; }
                best_d[at]=d; best_c[at]=it->second;
            }
        }
        const size_t base=static_cast<size_t>(sample)*4U;
        for(uint32_t i=0;i<4U;++i) {
            center_x[base+i]=best_c[i].x; center_y[base+i]=best_c[i].y;
            center_z[base+i]=best_c[i].z; squared_distances[base+i]=best_d[i];
        }
    }
    return 0;
} catch(...) { return 3; }
