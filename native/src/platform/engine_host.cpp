#include "snake/platform/engine_host.hpp"

#include <algorithm>
#include <chrono>
#include <cstddef>

namespace snake::platform {
namespace {

constexpr std::size_t snapshot_header_size = 14U;

game::WorldConfig make_config(
    const float width,
    const float height,
    const std::uint64_t seed,
    const std::uint32_t map_index,
    const std::uint32_t mode_index,
    const std::uint32_t ai_level,
    const std::uint32_t archetype_index) {
    game::WorldConfig config{};
    config.width = std::max(320.0F, width);
    config.height = std::max(180.0F, height);
    config.seed = seed;
    config.mode_index = std::min(2U, mode_index);
    config.ai_level = std::min(2U, ai_level);
    config.archetype_index = std::min(3U, archetype_index);
    config.bot_count = config.mode_index == 1U ? 4U : (config.ai_level == 2U ? 6U : 5U);
    switch (std::min(2U, map_index)) {
        case 1U:
            config.initial_food_count = 88U;
            config.base_speed *= 0.96F;
            break;
        case 2U:
            config.initial_food_count = 68U;
            config.base_speed *= 1.04F;
            break;
        default:
            config.initial_food_count = 78U;
            break;
    }
    return config;
}

}  // namespace

EngineHost::EngineHost(
    const float width,
    const float height,
    const std::uint64_t seed,
    const std::uint32_t map_index,
    const std::uint32_t mode_index,
    const std::uint32_t ai_level,
    const std::uint32_t archetype_index)
    : world_(make_config(width, height, seed, map_index, mode_index, ai_level, archetype_index)) {}

void EngineHost::advance(const std::int64_t frame_time_nanoseconds, const game::InputState& input) {
    if (last_frame_time_nanoseconds_ == 0 || frame_time_nanoseconds <= last_frame_time_nanoseconds_) {
        last_frame_time_nanoseconds_ = frame_time_nanoseconds;
        return;
    }
    const auto advance_result = clock_.advance(
        std::chrono::nanoseconds(frame_time_nanoseconds - last_frame_time_nanoseconds_));
    last_frame_time_nanoseconds_ = frame_time_nanoseconds;
    for (std::uint32_t step = 0; step < advance_result.simulation_steps; ++step) {
        static_cast<void>(world_.step(input, 1.0F / static_cast<float>(clock_.tick_rate())));
    }
}

void EngineHost::choose_upgrade(const std::uint32_t choice) noexcept {
    world_.choose_upgrade(choice);
}

void EngineHost::activate_ability(const std::uint32_t ability) {
    world_.activate_ability(ability);
}

std::size_t EngineHost::write_snapshot(float* output, const std::size_t capacity) const noexcept {
    if (output == nullptr || capacity < snapshot_header_size) return 0U;
    const auto& player = world_.player();
    const auto& foods = world_.foods();
    const auto& bots = world_.bots();
    std::size_t required = snapshot_header_size + player.segments.size() * 2U + foods.size() * 3U;
    for (const auto& bot : bots) required += 5U + bot.segments.size() * 2U;
    if (capacity < required) return 0U;

    output[0] = player.position.x;
    output[1] = player.position.y;
    output[2] = player.boost_energy;
    output[3] = static_cast<float>(player.score);
    output[4] = static_cast<float>(player.segments.size());
    output[5] = static_cast<float>(foods.size());
    output[6] = static_cast<float>(bots.size());
    output[7] = world_.elapsed_seconds();
    output[8] = static_cast<float>(world_.match_status());
    output[9] = static_cast<float>(world_.level());
    output[10] = static_cast<float>(world_.experience());
    output[11] = static_cast<float>(world_.experience_required());
    output[12] = world_.upgrade_pending() ? 1.0F : 0.0F;
    output[13] = static_cast<float>(world_.upgrade_set());

    std::size_t cursor = snapshot_header_size;
    for (const auto& segment : player.segments) {
        output[cursor++] = segment.x;
        output[cursor++] = segment.y;
    }
    for (const auto& food : foods) {
        output[cursor++] = food.position.x;
        output[cursor++] = food.position.y;
        output[cursor++] = static_cast<float>(food.value);
    }
    for (const auto& bot : bots) {
        output[cursor++] = bot.position.x;
        output[cursor++] = bot.position.y;
        output[cursor++] = static_cast<float>(bot.score);
        output[cursor++] = static_cast<float>(bot.segments.size());
        output[cursor++] = bot.alive ? 1.0F : 0.0F;
        for (const auto& segment : bot.segments) {
            output[cursor++] = segment.x;
            output[cursor++] = segment.y;
        }
    }
    return cursor;
}

}  // namespace snake::platform
