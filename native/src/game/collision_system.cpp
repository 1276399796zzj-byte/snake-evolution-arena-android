#include "snake/game/collision_system.hpp"

#include <algorithm>

namespace snake::game::collision {

core::Vec2 clamp_to_arena(
    const core::Vec2 position,
    const float width,
    const float height,
    const float margin) noexcept {
    const float safe_margin = std::max(0.0F, margin);
    const float maximum_x = std::max(safe_margin, width - safe_margin);
    const float maximum_y = std::max(safe_margin, height - safe_margin);
    return {
        std::clamp(position.x, safe_margin, maximum_x),
        std::clamp(position.y, safe_margin, maximum_y),
    };
}

bool circles_overlap(
    const core::Vec2 first,
    const float first_radius,
    const core::Vec2 second,
    const float second_radius) noexcept {
    const float radius = std::max(0.0F, first_radius) + std::max(0.0F, second_radius);
    return (first - second).length_squared() <= radius * radius;
}

}  // namespace snake::game::collision
