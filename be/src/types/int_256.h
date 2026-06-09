#pragma once
#include <cstdint>
#include <cstdlib>
#include <string>
#include <algorithm>

//#include "util/decimal_types.h"

namespace starrocks {

static inline unsigned __int128 abs(__int128 x) {
    return x < 0 ? -x : x;
}

static inline void multiply_128x128_to_256(unsigned __int128 a,unsigned __int128 b,unsigned __int128& low,unsigned __int128& high) {
    uint64_t a0 = static_cast<uint64_t>(a);
    uint64_t a1 = static_cast<uint64_t>(a >> 64);

    uint64_t b0 = static_cast<uint64_t>(b);
    uint64_t b1 = static_cast<uint64_t>(b >> 64);

    unsigned __int128 p00 = (unsigned __int128)a0 * b0;
    unsigned __int128 p01 = (unsigned __int128)a0 * b1;
    unsigned __int128 p10 = (unsigned __int128)a1 * b0;
    unsigned __int128 p11 = (unsigned __int128)a1 * b1;

    unsigned __int128 middle = (p00 >> 64)
                    + (uint64_t)p01
                    + (uint64_t)p10;

    low = (p00 & (((unsigned __int128)1 << 64) - 1))
        | (middle << 64);

    high = p11
        + (p01 >> 64)
        + (p10 >> 64)
        + (middle >> 64);
}

static inline std::string to_hex(unsigned __int128 x) {
    const char* hex = "0123456789abcdef";
    std::string s;
    s.reserve(32);

    for (int i = 0; i < 32; ++i) {
        int shift = (31 - i) * 4;
        s.push_back(hex[(x >> shift) & 0xF]);
    }

    return s;
}
static inline std::string to_string(unsigned __int128 x) {
    if (x == 0) return "0";

    std::string s;

    while (x > 0) {
        int digit = x % 10;
        s.push_back('0' + digit);
        x /= 10;
    }

    std::reverse(s.begin(), s.end());
    return s;
}
static inline std::string to_string(__int128 x) {
    if (x == 0) return "0";

    bool neg = x < 0;
    unsigned __int128 v = neg ? -x : x;

    std::string s;

    while (v > 0) {
        int digit = v % 10;
        s.push_back('0' + digit);
        v /= 10;
    }

    if (neg) s.push_back('-');

    std::reverse(s.begin(), s.end());
    return s;
}

static inline void negate_256(unsigned __int128& high, unsigned __int128& low) {
    high = ~high;
    low = ~low;

    ++low;

    if (low == 0) {
        ++high;
    }
}

static inline void abs_256(unsigned __int128& high, unsigned __int128& low) {
    if ((high >> 127) != 0) {
        negate_256(high, low);
    }
}

static inline unsigned __int128 div_256_by_128_to_128(
    unsigned __int128 high,
    unsigned __int128 low,
    unsigned __int128 divisor,
    bool& overflow) {

    unsigned __int128 quotient = 0;
    unsigned __int128 remainder = 0;

    overflow = false;

    for (int i = 255; i >= 0; --i) {

        remainder <<= 1;

        if (i >= 128) {
            remainder |= (high >> (i - 128)) & 1;
        } else {
            remainder |= (low >> i) & 1;
        }

        if (remainder >= divisor) {
            remainder -= divisor;

            if (i < 128) {
                quotient |= (unsigned __int128)1 << i;
            } else {
                // overflow into bits above 128
                overflow = true;
            }
        }
    }

    return quotient;
}

static inline void signed_multiply_128x128_to_256(__int128 x,
                                                  __int128 y,
                                                  __int128& output_low,
                                                  __int128& output_high) {
    
    bool negative = (x < 0) ^ (y < 0);

    
    unsigned __int128 a = abs(x);
    unsigned __int128 b = abs(y);

    
    unsigned __int128 low;
    unsigned __int128 high;
    multiply_128x128_to_256(a, b, low, high);

    if (negative) {
        negate_256(high, low);
    }

    output_low = static_cast<__int128>(low);
    output_high = static_cast<__int128>(high);
}

static inline __int128 signed_div_256_by_128_to_128(
    __int128 high,
    __int128 low,
    __int128 divisor,
    bool& overflow) {
    static constexpr __int128 INT128_MAX = (__int128)((unsigned __int128(1) << 127) - 1);
    static constexpr __int128 INT128_MIN = -INT128_MAX - 1;
    
    bool neg_result = ((high < 0) ^ (divisor < 0));
    

    unsigned __int128 u_high = static_cast<unsigned __int128>(high);
    unsigned __int128 u_low = static_cast<unsigned __int128>(low);
    abs_256(u_high, u_low);
    unsigned __int128 u_div = abs(divisor);

    bool u_overflow = false;
    unsigned __int128 uq = div_256_by_128_to_128(u_high, u_low, u_div, u_overflow);

    // 4. apply sign
    __int128 q = neg_result ? -(__int128)uq : (__int128)uq;

    // 5. overflow check (signed 128-bit range)
    overflow = false;
    if ((uq >> 127) != 0 || q > INT128_MAX || q < INT128_MIN) {
        overflow = true;
    }

    // 6. propagate unsigned overflow too
    overflow = overflow || u_overflow;

    return q;
}

} // namespace starrocks

