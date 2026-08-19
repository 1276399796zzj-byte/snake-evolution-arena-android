#include "snake/game/snake_system.hpp"

#include "snake/game/collision_system.hpp"
#include "snake/game/world.hpp"

#include <algorithm>
#include <cmath>

namespace snake::game::systems {

void advance_snake(
    Snake& snake,
    const InputState& input,
    const WorldConfig& config,
    const float delta_seconds) {
    if (!snake.alive || delta_seconds <= 0.0F) {
        return;
    }

    const core::Vec2 desired = input.desired_direction.normalized();
    if (desired.length_squared() > 0.0F) {
        const float turn_alpha = std::clamp(config.turn_response * delta_seconds, 0.0F, 1.0F);
        const core::Vec2 blended = core::lerp(snake.direction, desired, turn_alpha).normalized();
        if (blended.length_squared() > 0.0F) {
            snake.direction = blended;
        }
    }

    const bool boosting = input.boost && snake.boost_energy > 0.0F;
    const float speed_scale = boosting ? config.boost_multiplier : 1.0F;
    if (boosting) {
        snake.boost_energy = std::max(0.0F, snake.boost_energy - config.boost_drain_per_second * delta_seconds);
    } else {
        snake.boost_energy = std::min(100.0F, snake.boost_energy + config.boost_recharge_per_second * delta_seconds);
    }

    snake.position = collision::clamp_to_arena(
        snake.position + snake.direction * (config.base_speed * speed_scale * delta_seconds),
        config.width,
        config.height,
        config.boundary_margin);

    const auto desired_segments = static_cast<std::size_t>(std::max(2.0F, std::floor(snake.target_segment_count)));
    if (snake.segments.capacity() < desired_segments) {
        snake.segments.reserve(std::max<std::size_t>(desired_segments, 256U));
    }
    while (snake.segments.size() < desired_segments) {
        snake.segments.push_back(snake.segments.empty() ? snake.position : snake.segments.back());
    }

    if (snake.segments.empty()) {
        return;
    }
    snake.segments.front() = snake.position;
    for (std::size_t index = 1; index < snake.segments.size(); ++index) {
        const core::Vec2 offset = snake.segments[index - 1U] - snake.segments[index];
        const float distance = offset.length();
        if (distance > config.segment_spacing) {
            snake.segments[index] = snake.segments[index - 1U] - offset.normalized() * config.segment_spacing;
        }
    }
}

}  // namespace snake::game::systems
