#include "snake/game/world.hpp"

#include "snake/game/collision_system.hpp"
#include "snake/game/snake_system.hpp"

#include <algorithm>
#include <utility>

namespace snake::game {

World::World(WorldConfig config)
    : config_(config), rng_(config.seed) {
    initialize_player();
    initialize_foods();
}

World::World(WorldConfig config, std::vector<Food> authored_foods)
    : config_(config), rng_(config.seed), foods_(std::move(authored_foods)) {
    initialize_player();
    for (const auto& food : foods_) {
        next_food_id_ = std::max(next_food_id_, food.id + 1U);
    }
}

void World::initialize_player() {
    player_.position = {config_.width * 0.5F, config_.height * 0.5F};
    player_.direction = {1.0F, 0.0F};
    player_.boost_energy = 100.0F;
    player_.target_segment_count = static_cast<float>(std::max<std::size_t>(2U, config_.initial_segments));
    player_.segments.reserve(std::max<std::size_t>(config_.initial_segments, 256U));
    for (std::size_t index = 0; index < config_.initial_segments; ++index) {
        player_.segments.push_back({
            player_.position.x - static_cast<float>(index) * config_.segment_spacing,
            player_.position.y,
        });
    }
}

void World::initialize_foods() {
    foods_.resize(config_.initial_food_count);
    for (auto& food : foods_) {
        respawn_food(food);
    }
}

void World::respawn_food(Food& food) {
    food.id = next_food_id_++;
    food.value = static_cast<std::uint16_t>(1U + rng_.bounded(3U));
    const float usable_width = std::max(1.0F, config_.width - config_.boundary_margin * 2.0F);
    const float usable_height = std::max(1.0F, config_.height - config_.boundary_margin * 2.0F);

    for (int attempt = 0; attempt < 8; ++attempt) {
        food.position = {
            config_.boundary_margin + rng_.unit_float() * usable_width,
            config_.boundary_margin + rng_.unit_float() * usable_height,
        };
        if (!collision::circles_overlap(
                player_.position,
                config_.pickup_radius * 2.0F,
                food.position,
                config_.food_radius)) {
            break;
        }
    }
}

StepResult World::step(const InputState& input, const float delta_seconds) {
    StepResult result{};
    systems::advance_snake(player_, input, config_, std::max(0.0F, delta_seconds));

    for (auto& food : foods_) {
        if (!collision::circles_overlap(
                player_.position,
                config_.pickup_radius,
                food.position,
                config_.food_radius)) {
            continue;
        }

        const std::uint32_t value = food.value;
        player_.score += value;
        player_.target_segment_count += static_cast<float>(value) * 0.65F;
        result.collected_food += 1U;
        result.score_delta += value;
        respawn_food(food);
    }
    return result;
}

}  // namespace snake::game
