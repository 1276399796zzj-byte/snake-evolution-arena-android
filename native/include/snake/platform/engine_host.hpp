#pragma once

#include "snake/core/fixed_step.hpp"
#include "snake/game/world.hpp"

#include <cstddef>
#include <cstdint>

namespace snake::platform {

class EngineHost {
  public:
    EngineHost(float width, float height, std::uint64_t seed);

    void advance(std::int64_t frame_time_nanoseconds, const game::InputState& input);
    [[nodiscard]] std::size_t write_snapshot(float* output, std::size_t capacity) const noexcept;

  private:
    core::FixedStepClock clock_{};
    game::World world_;
    std::int64_t last_frame_time_nanoseconds_{0};
};

}  // namespace snake::platform
