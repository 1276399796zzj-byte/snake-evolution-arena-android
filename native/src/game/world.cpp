#include "snake/game/world.hpp"

#include "snake/game/collision_system.hpp"
#include "snake/game/snake_system.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <utility>

namespace snake::game {
namespace {

constexpr float pi = 3.14159265358979323846F;
constexpr float snake_collision_radius = 9.0F;
constexpr std::size_t collision_segment_start = 4U;
constexpr float maximum_segments = 320.0F;

WorldConfig apply_archetype(WorldConfig config) {
    switch (config.archetype_index) {
        case 0U:
            config.base_speed *= 1.10F;
            config.turn_response *= 1.08F;
            break;
        case 1U:
            config.initial_segments += 8U;
            config.boost_drain_per_second *= 0.90F;
            break;
        case 2U:
            config.boost_recharge_per_second *= 1.18F;
            config.score_multiplier *= 1.08F;
            break;
        case 3U:
            config.pickup_radius *= 1.30F;
            config.experience_multiplier *= 1.12F;
            break;
        default:
            break;
    }
    return config;
}

bool head_hits_body(const core::Vec2 head, const Snake& target) {
    if (!target.alive || target.segments.size() <= collision_segment_start) return false;
    for (std::size_t index = collision_segment_start; index < target.segments.size(); ++index) {
        if (collision::circles_overlap(
                head,
                snake_collision_radius,
                target.segments[index],
                snake_collision_radius * 0.82F)) {
            return true;
        }
    }
    return false;
}

}  // namespace

World::World(WorldConfig config)
    : config_(apply_archetype(config)), rng_(config.seed) {
    initialize_player();
    initialize_foods();
    initialize_bots();
}

World::World(WorldConfig config, std::vector<Food> authored_foods)
    : config_(apply_archetype(config)), rng_(config.seed), foods_(std::move(authored_foods)) {
    initialize_player();
    for (const auto& food : foods_) next_food_id_ = std::max(next_food_id_, food.id + 1U);
    initialize_bots();
}

void World::initialize_player() {
    respawn_snake(player_, 0U, true);
}

void World::initialize_bots() {
    bots_.resize(config_.bot_count);
    for (std::size_t index = 0; index < bots_.size(); ++index) {
        respawn_snake(bots_[index], index, false);
    }
}

void World::respawn_snake(Snake& snake, const std::size_t spawn_index, const bool player) {
    const std::uint32_t preserved_score = snake.score;
    if (player) {
        snake.position = {config_.width * 0.5F, config_.height * 0.5F};
        snake.direction = {1.0F, 0.0F};
    } else {
        const float divisor = static_cast<float>(std::max<std::size_t>(1U, config_.bot_count));
        const float angle = 2.0F * pi * static_cast<float>(spawn_index) / divisor + pi * 0.35F;
        const float radius = std::min(config_.width, config_.height) * 0.32F;
        snake.position = {
            config_.width * 0.5F + std::cos(angle) * radius,
            config_.height * 0.5F + std::sin(angle) * radius,
        };
        snake.direction = core::Vec2{
            config_.width * 0.5F - snake.position.x,
            config_.height * 0.5F - snake.position.y,
        }.normalized();
    }

    snake.boost_energy = 100.0F;
    snake.alive = true;
    snake.score = preserved_score;
    const std::size_t segment_count = player ? config_.initial_segments : 14U + spawn_index % 5U;
    snake.target_segment_count = static_cast<float>(std::max<std::size_t>(2U, segment_count));
    snake.segments.clear();
    snake.segments.reserve(std::max<std::size_t>(segment_count, 128U));
    for (std::size_t index = 0; index < segment_count; ++index) {
        snake.segments.push_back(
            snake.position - snake.direction * (static_cast<float>(index) * config_.segment_spacing));
    }
}

void World::initialize_foods() {
    foods_.resize(config_.initial_food_count);
    for (auto& food : foods_) respawn_food(food);
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
                config_.food_radius)) break;
    }
}

InputState World::bot_input(const std::size_t bot_index) const {
    if (bot_index >= bots_.size()) return {};
    const Snake& bot = bots_[bot_index];
    core::Vec2 desired = bot.direction;
    float nearest_distance_squared = std::numeric_limits<float>::max();
    for (const auto& food : foods_) {
        const core::Vec2 delta = food.position - bot.position;
        const float distance_squared = delta.length_squared();
        if (distance_squared < nearest_distance_squared) {
            nearest_distance_squared = distance_squared;
            desired = delta.normalized();
        }
    }

    constexpr float edge_zone = 120.0F;
    core::Vec2 edge_avoidance{};
    if (bot.position.x < edge_zone) edge_avoidance.x += 1.0F;
    if (bot.position.x > config_.width - edge_zone) edge_avoidance.x -= 1.0F;
    if (bot.position.y < edge_zone) edge_avoidance.y += 1.0F;
    if (bot.position.y > config_.height - edge_zone) edge_avoidance.y -= 1.0F;
    desired = desired + edge_avoidance * 1.8F;

    const core::Vec2 player_delta = bot.position - player_.position;
    if (player_.alive && player_delta.length_squared() < 145.0F * 145.0F) {
        desired = desired + player_delta.normalized() * 0.72F;
    }
    const float wobble = std::sin(elapsed_seconds_ * (0.72F + bot_index * 0.08F) + bot_index * 1.77F);
    desired = desired + core::Vec2{-desired.y, desired.x} * (0.08F + 0.035F * wobble);

    const float boost_gate = config_.ai_level == 0U ? 0.96F : (config_.ai_level == 1U ? 0.82F : 0.66F);
    const bool boost = bot.boost_energy > 28.0F &&
        std::sin(elapsed_seconds_ * 0.93F + bot_index * 2.2F) > boost_gate;
    return {desired.normalized(), boost};
}

void World::collect_food(Snake& snake, const bool player, StepResult& result) {
    if (!snake.alive) return;
    for (auto& food : foods_) {
        if (!collision::circles_overlap(
                snake.position,
                config_.pickup_radius,
                food.position,
                config_.food_radius)) continue;

        const std::uint32_t raw_value = food.value;
        const auto score_value = static_cast<std::uint32_t>(std::max(
            1.0F,
            std::round(static_cast<float>(raw_value) * (player ? config_.score_multiplier : 1.0F))));
        snake.score += score_value;
        snake.target_segment_count = std::min(
            maximum_segments,
            snake.target_segment_count + static_cast<float>(raw_value) * 0.65F *
                (player ? config_.growth_multiplier : 1.0F));
        if (player) {
            result.collected_food += 1U;
            result.score_delta += score_value;
            const auto experience_value = static_cast<std::uint32_t>(std::max(
                1.0F,
                std::round(static_cast<float>(raw_value) * config_.experience_multiplier)));
            add_experience(experience_value);
        }
        respawn_food(food);
    }
}

void World::resolve_snake_collisions(StepResult& result) {
    if (!player_.alive) return;
    for (std::size_t index = 0; index < bots_.size(); ++index) {
        Snake& bot = bots_[index];
        if (!bot.alive) continue;

        const bool player_hit = head_hits_body(player_.position, bot) ||
            (collision::circles_overlap(
                 player_.position,
                 snake_collision_radius,
                 bot.position,
                 snake_collision_radius) &&
             player_.segments.size() < bot.segments.size());
        if (player_hit) {
            if (shield_remaining_seconds_ > 0.0F) {
                player_.score += 3U;
                result.score_delta += 3U;
                result.defeated_bots += 1U;
                add_experience(3U);
                bot.score = bot.score > 2U ? bot.score - 2U : 0U;
                respawn_snake(bot, index, false);
                continue;
            }
            result.player_defeated = true;
            if (config_.mode_index == 0U) {
                player_.score = player_.score > 5U ? player_.score - 5U : 0U;
                respawn_snake(player_, 0U, true);
            } else {
                player_.alive = false;
                match_status_ = MatchStatus::defeat;
            }
            return;
        }

        const bool bot_hit = head_hits_body(bot.position, player_) ||
            (collision::circles_overlap(
                 bot.position,
                 snake_collision_radius,
                 player_.position,
                 snake_collision_radius) &&
             bot.segments.size() <= player_.segments.size());
        if (bot_hit) {
            player_.score += 5U;
            result.score_delta += 5U;
            result.defeated_bots += 1U;
            add_experience(5U);
            bot.score = bot.score > 3U ? bot.score - 3U : 0U;
            respawn_snake(bot, index, false);
        }
    }
}

void World::add_experience(const std::uint32_t amount) noexcept {
    experience_ += amount;
    if (upgrade_pending_ || experience_ < experience_required_) return;
    experience_ -= experience_required_;
    level_ += 1U;
    upgrade_set_ = (level_ - 2U) % 4U;
    const float next_requirement = std::round(static_cast<float>(experience_required_) * 1.42F + 8.0F);
    experience_required_ = static_cast<std::uint32_t>(std::min(420.0F, next_requirement));
    upgrade_pending_ = true;
}

void World::choose_upgrade(const std::uint32_t choice) noexcept {
    if (!upgrade_pending_) return;
    const std::uint32_t selected = choice % 3U;
    switch (upgrade_set_) {
        case 0U:
            if (selected == 0U) config_.base_speed *= 1.08F;
            if (selected == 1U) config_.pickup_radius *= 1.18F;
            if (selected == 2U) {
                config_.boost_drain_per_second *= 0.88F;
                config_.boost_recharge_per_second *= 1.12F;
            }
            break;
        case 1U:
            if (selected == 0U) player_.target_segment_count = std::min(maximum_segments, player_.target_segment_count + 8.0F);
            if (selected == 1U) config_.turn_response *= 1.16F;
            if (selected == 2U) player_.boost_energy = 100.0F;
            break;
        case 2U:
            if (selected == 0U) config_.score_multiplier *= 1.15F;
            if (selected == 1U) config_.growth_multiplier *= 1.20F;
            if (selected == 2U) config_.experience_multiplier *= 1.18F;
            break;
        default:
            if (selected == 0U) config_.food_radius *= 1.12F;
            if (selected == 1U) config_.base_speed *= 1.05F;
            if (selected == 2U) {
                config_.pickup_radius *= 1.10F;
                config_.boost_recharge_per_second *= 1.08F;
            }
            break;
    }
    upgrade_pending_ = false;
}

void World::activate_ability(const std::uint32_t ability) {
    if (match_status_ != MatchStatus::active || !player_.alive || upgrade_pending_) return;
    if (ability == 1U) {
        player_.boost_energy = std::max(player_.boost_energy, 38.0F);
        return;
    }
    if (ability == 2U) {
        shield_remaining_seconds_ = std::max(shield_remaining_seconds_, 3.2F);
        return;
    }
    if (ability != 0U) return;

    const float pulse_radius = std::clamp(
        std::min(config_.width, config_.height) * 0.34F,
        180.0F,
        460.0F);
    const float pulse_radius_squared = pulse_radius * pulse_radius;
    for (auto& food : foods_) {
        if ((food.position - player_.position).length_squared() > pulse_radius_squared) continue;
        const std::uint32_t raw_value = food.value;
        const auto score_value = static_cast<std::uint32_t>(std::max(
            1.0F,
            std::round(static_cast<float>(raw_value) * config_.score_multiplier)));
        player_.score += score_value;
        player_.target_segment_count = std::min(
            maximum_segments,
            player_.target_segment_count +
                static_cast<float>(raw_value) * 0.42F * config_.growth_multiplier);
        const auto experience_value = static_cast<std::uint32_t>(std::max(
            1.0F,
            std::round(static_cast<float>(raw_value) * config_.experience_multiplier)));
        add_experience(experience_value);
        respawn_food(food);
    }

    for (std::size_t index = 0; index < bots_.size(); ++index) {
        Snake& bot = bots_[index];
        if (!bot.alive || (bot.position - player_.position).length_squared() > pulse_radius_squared) continue;
        player_.score += 4U;
        add_experience(3U);
        bot.score = bot.score > 3U ? bot.score - 3U : 0U;
        respawn_snake(bot, index, false);
    }
}

void World::update_match_status() noexcept {
    if (match_status_ != MatchStatus::active) return;
    if (!player_.alive) {
        match_status_ = MatchStatus::defeat;
        return;
    }
    const float time_limit = config_.mode_index == 0U ? 180.0F : (config_.mode_index == 1U ? 600.0F : -1.0F);
    if (time_limit < 0.0F || elapsed_seconds_ < time_limit) return;
    if (config_.mode_index == 0U) {
        std::uint32_t best_bot_score = 0U;
        for (const auto& bot : bots_) best_bot_score = std::max(best_bot_score, bot.score);
        match_status_ = player_.score >= best_bot_score ? MatchStatus::victory : MatchStatus::defeat;
    } else {
        match_status_ = MatchStatus::victory;
    }
}

StepResult World::step(const InputState& input, const float delta_seconds) {
    StepResult result{};
    if (match_status_ != MatchStatus::active || upgrade_pending_) return result;
    const float safe_delta = std::max(0.0F, delta_seconds);
    elapsed_seconds_ += safe_delta;
    shield_remaining_seconds_ = std::max(0.0F, shield_remaining_seconds_ - safe_delta);

    if (player_.alive) systems::advance_snake(player_, input, config_, safe_delta);
    for (std::size_t index = 0; index < bots_.size(); ++index) {
        WorldConfig bot_config = config_;
        const float difficulty_speed = config_.ai_level == 0U ? 0.86F : (config_.ai_level == 1U ? 0.96F : 1.07F);
        bot_config.base_speed *= difficulty_speed * (0.98F + static_cast<float>(index % 3U) * 0.025F);
        bot_config.turn_response *= config_.ai_level == 2U ? 1.12F : 0.94F;
        systems::advance_snake(bots_[index], bot_input(index), bot_config, safe_delta);
    }

    collect_food(player_, true, result);
    for (auto& bot : bots_) collect_food(bot, false, result);
    resolve_snake_collisions(result);
    update_match_status();
    return result;
}

}  // namespace snake::game
