#pragma once

#include <algorithm>
#include <cmath>

namespace snake::core {

struct Vec2 {
    float x{0.0F};
    float y{0.0F};

    [[nodiscard]] constexpr Vec2 operator+(const Vec2 other) const noexcept {
        return {x + other.x, y + other.y};
    }

    [[nodiscard]] constexpr Vec2 operator-(const Vec2 other) const noexcept {
        return {x - other.x, y - other.y};
    }

    [[nodiscard]] constexpr Vec2 operator*(const float scale) const noexcept {
        return {x * scale, y * scale};
    }

    [[nodiscard]] constexpr float length_squared() const noexcept {
        return x * x + y * y;
    }

    [[nodiscard]] float length() const noexcept {
        return std::sqrt(length_squared());
    }

    [[nodiscard]] Vec2 normalized() const noexcept {
        const float magnitude = length();
        if (magnitude <= 1.0e-6F) {
            return {};
        }
        return {x / magnitude, y / magnitude};
    }
};

[[nodiscard]] constexpr float clamp01(const float value) noexcept {
    return std::clamp(value, 0.0F, 1.0F);
}

[[nodiscard]] constexpr Vec2 lerp(const Vec2 from, const Vec2 to, const float alpha) noexcept {
    const float t = clamp01(alpha);
    return from + (to - from) * t;
}

}  // namespace snake::core
