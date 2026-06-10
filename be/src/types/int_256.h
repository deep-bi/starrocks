#pragma once
#include <cstdint>
#include <cstdlib>
#include <string>
#include <algorithm>

//#include "util/decimal_types.h"
typedef __int128 int128_t;
typedef unsigned __int128 uint128_t;

namespace starrocks {

static inline uint128_t abs(int128_t x) {
    return x < 0 ? -x : x;
}

static inline void multiply_128x128_to_256(uint128_t a,uint128_t b,uint128_t& low,uint128_t& high) {
    uint64_t a0 = static_cast<uint64_t>(a);
    uint64_t a1 = static_cast<uint64_t>(a >> 64);

    uint64_t b0 = static_cast<uint64_t>(b);
    uint64_t b1 = static_cast<uint64_t>(b >> 64);

    uint128_t p00 = (uint128_t)a0 * b0;
    uint128_t p01 = (uint128_t)a0 * b1;
    uint128_t p10 = (uint128_t)a1 * b0;
    uint128_t p11 = (uint128_t)a1 * b1;

    uint128_t middle = (p00 >> 64)
                    + (uint64_t)p01
                    + (uint64_t)p10;

    low = (p00 & (((uint128_t)1 << 64) - 1))
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
static inline std::string to_string(int128_t x) {
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

static inline void negate_256(uint128_t& high, uint128_t& low) {
    high = ~high;
    low = ~low;

    ++low;

    if (low == 0) {
        ++high;
    }
}

static inline void abs_256(uint128_t& high, uint128_t& low) {
    if ((high >> 127) != 0) {
        negate_256(high, low);
    }
}

static inline uint128_t div_256_by_128_to_128(
    uint128_t high,
    uint128_t low,
    uint128_t divisor,
    bool& overflow) {

    uint128_t quotient = 0;
    uint128_t remainder = 0;

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
                quotient |= (uint128_t)1 << i;
            } else {
                // overflow into bits above 128
                overflow = true;
            }
        }
    }

    return quotient;
}

static inline void signed_multiply_128x128_to_256(int128_t x,
                                                  int128_t y,
                                                  int128_t& output_low,
                                                  int128_t& output_high) {
    
    bool negative = (x < 0) ^ (y < 0);

    
    uint128_t a = abs(x);
    uint128_t b = abs(y);

    
    uint128_t low;
    uint128_t high;
    multiply_128x128_to_256(a, b, low, high);

    if (negative) {
        negate_256(high, low);
    }

    output_low = static_cast<int128_t>(low);
    output_high = static_cast<int128_t>(high);
}

static inline int128_t signed_div_256_by_128_to_128(
    int128_t high,
    int128_t low,
    int128_t divisor, bool& overflow) {
    static constexpr int128_t INT128_MAX = (int128_t)((uint128_t(1) << 127) - 1);
    static constexpr int128_t INT128_MIN = -INT128_MAX - 1;
    
    bool neg_result = ((high < 0) ^ (divisor < 0));
    

    uint128_t u_high = static_cast<uint128_t>(high);
    uint128_t u_low = static_cast<uint128_t>(low);
    abs_256(u_high, u_low);
    uint128_t u_div = abs(divisor);

    bool u_overflow = false;
    uint128_t uq = div_256_by_128_to_128(u_high, u_low, u_div, u_overflow);

    // 4. apply sign
    int128_t q = neg_result ? -(int128_t)uq : (int128_t)uq;

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

