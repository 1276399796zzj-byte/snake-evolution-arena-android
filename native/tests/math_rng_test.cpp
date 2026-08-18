#include "snake/core/math.hpp"
#include "snake/core/rng.hpp"

#include <cmath>
#include <cstdlib>
#include <iostream>

namespace {

bool nearly_equal(const float left, const float right) {
    return std::abs(left - right) < 1.0e-5F;
}

}  // namespace

int main() {
    snake::core::DeterministicRng first(0xC0FFEEU, 7U);
    snake::core::DeterministicRng second(0xC0FFEEU, 7U);
    for (int index = 0; index < 256; ++index) {
        if (first.next_u32() != second.next_u32()) {
            std::cerr << "same seed must produce the same sequence\n";
            return EXIT_FAILURE;
        }
    }

    snake::core::DeterministicRng range_rng(42U);
    for (int index = 0; index < 1000; ++index) {
        if (range_rng.bounded(7U) >= 7U) {
            std::cerr << "bounded RNG escaped its upper limit\n";
            return EXIT_FAILURE;
        }
        const float unit = range_rng.unit_float();
        if (unit < 0.0F || unit >= 1.0F) {
            std::cerr << "unit float escaped [0, 1)\n";
            return EXIT_FAILURE;
        }
    }

    const snake::core::Vec2 vector{3.0F, 4.0F};
    const auto normalized = vector.normalized();
    if (!nearly_equal(vector.length(), 5.0F) || !nearly_equal(normalized.length(), 1.0F)) {
        std::cerr << "vector normalization failed\n";
        return EXIT_FAILURE;
    }
    if (snake::core::Vec2{}.normalized().length_squared() != 0.0F) {
        std::cerr << "zero vector normalization must stay finite\n";
        return EXIT_FAILURE;
    }
    const auto midpoint = snake::core::lerp({0.0F, 0.0F}, {10.0F, -4.0F}, 0.5F);
    if (!nearly_equal(midpoint.x, 5.0F) || !nearly_equal(midpoint.y, -2.0F)) {
        std::cerr << "vector interpolation failed\n";
        return EXIT_FAILURE;
    }

    return EXIT_SUCCESS;
}
