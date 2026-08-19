#pragma once

#include "snake/core/math.hpp"

#include <cstdint>
#include <vector>

namespace snake::game {

struct InputState {
    core::Vec2 desired_direction{1.0F, 0.0F};
    bool boost{false};
};

struct Food {
    std::uint32_t id{0U};
    core::Vec2 position{};
    std::uint16_t value{1U};
};

struct Snake {
    core::Vec2 position{};
    core::Vec2 direction{1.0F, 0.0F};
    std::vector<core::Vec2> segments{};
    float boost_energy{100.0F};
    float target_segment_count{18.0F};
    std::uint32_t score{0U};
    bool alive{true};
};

struct StepResult {
    std::uint32_t collected_food{0U};
    std::uint32_t score_delta{0U};
};

}  // namespace snake::game
