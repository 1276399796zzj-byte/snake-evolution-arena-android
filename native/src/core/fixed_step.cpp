#include "snake/core/fixed_step.hpp"

#include <algorithm>

namespace snake::core {

FixedStepClock::FixedStepClock(
    const std::uint32_t tick_rate,
    const std::chrono::nanoseconds max_frame_delta) noexcept
    : tick_rate_(std::max(1U, tick_rate)),
      max_frame_delta_(std::max(std::chrono::nanoseconds::zero(), max_frame_delta)) {}

FrameAdvance FixedStepClock::advance(std::chrono::nanoseconds frame_delta) noexcept {
    frame_delta = std::clamp(frame_delta, std::chrono::nanoseconds::zero(), max_frame_delta_);

    const auto delta_count = static_cast<std::uint64_t>(frame_delta.count());
    const std::uint64_t scaled_time = scaled_remainder_ + delta_count * tick_rate_;
    const auto steps = static_cast<std::uint32_t>(scaled_time / nanoseconds_per_second);
    scaled_remainder_ = scaled_time % nanoseconds_per_second;
    total_steps_ += steps;

    return {
        steps,
        static_cast<double>(scaled_remainder_) / static_cast<double>(nanoseconds_per_second),
        frame_delta,
    };
}

void FixedStepClock::reset() noexcept {
    scaled_remainder_ = 0U;
}

double FixedStepClock::logic_seconds() const noexcept {
    return static_cast<double>(total_steps_) / static_cast<double>(tick_rate_);
}

}  // namespace snake::core
