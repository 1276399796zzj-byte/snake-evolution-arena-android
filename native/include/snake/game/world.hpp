#pragma once

#include "snake/core/rng.hpp"
#include "snake/game/entities.hpp"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace snake::game {

struct WorldConfig {
    float width{1920.0F};
    float height{1080.0F};
    float boundary_margin{24.0F};
    float base_speed{210.0F};
    float boost_multiplier{1.65F};
    float turn_response{9.0F};
    float segment_spacing{13.0F};
    float pickup_radius{27.0F};
    float food_radius{7.0F};
    float boost_drain_per_second{34.0F};
    float boost_recharge_per_second{17.0F};
    std::size_t initial_segments{18U};
    std::size_t initial_food_count{72U};
    std::uint64_t seed{0x5EED1234ULL};
};

class World {
  public:
    explicit World(WorldConfig config = {});
    World(WorldConfig config, std::vector<Food> authored_foods);

    [[nodiscard]] StepResult step(const InputState& input, float delta_seconds);

    [[nodiscard]] const WorldConfig& config() const noexcept { return config_; }
    [[nodiscard]] const Snake& player() const noexcept { return player_; }
    [[nodiscard]] const std::vector<Food>& foods() const noexcept { return foods_; }

  private:
    void initialize_player();
    void initialize_foods();
    void respawn_food(Food& food);

    WorldConfig config_{};
    core::DeterministicRng rng_;
    Snake player_{};
    std::vector<Food> foods_{};
    std::uint32_t next_food_id_{1U};
};

}  // namespace snake::game
