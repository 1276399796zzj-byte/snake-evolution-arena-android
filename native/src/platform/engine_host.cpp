#include "snake/platform/engine_host.hpp"

#include <algorithm>
#include <chrono>

namespace snake::platform {
namespace {

game::WorldConfig make_config(const float width, const float height, const std::uint64_t seed) {
    game::WorldConfig config{};
    config.width = std::max(320.0F, width);
    config.height = std::max(180.0F, height);
    config.seed = seed;
    return config;
}

}  // namespace

EngineHost::EngineHost(const float width, const float height, const std::uint64_t seed)
    : world_(make_config(width, height, seed)) {}

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

std::size_t EngineHost::write_snapshot(float* output, const std::size_t capacity) const noexcept {
    if (output == nullptr || capacity < 6U) {
        return 0U;
    }
    const auto& player = world_.player();
    const auto& foods = world_.foods();
    const std::size_t segment_capacity = (capacity - 6U) / 2U;
    const std::size_t segment_count = std::min(player.segments.size(), segment_capacity);
    const std::size_t after_segments = 6U + segment_count * 2U;
    const std::size_t food_capacity = capacity > after_segments ? (capacity - after_segments) / 3U : 0U;
    const std::size_t food_count = std::min(foods.size(), food_capacity);

    output[0] = player.position.x;
    output[1] = player.position.y;
    output[2] = player.boost_energy;
    output[3] = static_cast<float>(player.score);
    output[4] = static_cast<float>(segment_count);
    output[5] = static_cast<float>(food_count);
    std::size_t cursor = 6U;
    for (std::size_t index = 0; index < segment_count; ++index) {
        output[cursor++] = player.segments[index].x;
        output[cursor++] = player.segments[index].y;
    }
    for (std::size_t index = 0; index < food_count; ++index) {
        output[cursor++] = foods[index].position.x;
        output[cursor++] = foods[index].position.y;
        output[cursor++] = static_cast<float>(foods[index].value);
    }
    return cursor;
}

}  // namespace snake::platform
