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
    float growth_multiplier{1.0F};
    float score_multiplier{1.0F};
    float experience_multiplier{1.0F};
    std::size_t initial_segments{18U};
    std::size_t initial_food_count{72U};
    std::size_t bot_count{0U};
    std::uint32_t ai_level{1U};
    std::uint32_t mode_index{0U};
    std::uint32_t archetype_index{4U};
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
    [[nodiscard]] const std::vector<Snake>& bots() const noexcept { return bots_; }
    [[nodiscard]] float elapsed_seconds() const noexcept { return elapsed_seconds_; }
    [[nodiscard]] MatchStatus match_status() const noexcept { return match_status_; }
    [[nodiscard]] std::uint32_t level() const noexcept { return level_; }
    [[nodiscard]] std::uint32_t experience() const noexcept { return experience_; }
    [[nodiscard]] std::uint32_t experience_required() const noexcept { return experience_required_; }
    [[nodiscard]] bool upgrade_pending() const noexcept { return upgrade_pending_; }
    [[nodiscard]] std::uint32_t upgrade_set() const noexcept { return upgrade_set_; }
    [[nodiscard]] float shield_remaining_seconds() const noexcept { return shield_remaining_seconds_; }

    void choose_upgrade(std::uint32_t choice) noexcept;
    void activate_ability(std::uint32_t ability);

  private:
    void initialize_player();
    void initialize_foods();
    void initialize_bots();
    void respawn_food(Food& food);
    void respawn_snake(Snake& snake, std::size_t spawn_index, bool player);
    [[nodiscard]] InputState bot_input(std::size_t bot_index) const;
    void collect_food(Snake& snake, bool player, StepResult& result);
    void resolve_snake_collisions(StepResult& result);
    void add_experience(std::uint32_t amount) noexcept;
    void update_match_status() noexcept;

    WorldConfig config_{};
    core::DeterministicRng rng_;
    Snake player_{};
    std::vector<Food> foods_{};
    std::vector<Snake> bots_{};
    std::uint32_t next_food_id_{1U};
    float elapsed_seconds_{0.0F};
    MatchStatus match_status_{MatchStatus::active};
    std::uint32_t level_{1U};
    std::uint32_t experience_{0U};
    std::uint32_t experience_required_{40U};
    bool upgrade_pending_{false};
    std::uint32_t upgrade_set_{0U};
    float shield_remaining_seconds_{0.0F};
};

}  // namespace snake::game
