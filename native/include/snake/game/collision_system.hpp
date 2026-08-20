#pragma once

#include "snake/core/math.hpp"

namespace snake::game::collision {

[[nodiscard]] core::Vec2 clamp_to_arena(core::Vec2 position, float width, float height, float margin) noexcept;
[[nodiscard]] bool circles_overlap(core::Vec2 first, float first_radius, core::Vec2 second, float second_radius) noexcept;

}  // namespace snake::game::collision
