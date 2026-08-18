#pragma once

#include <chrono>
#include <cstdint>

namespace snake::core {

struct FrameAdvance {
    std::uint32_t simulation_steps{0U};
    double interpolation_alpha{0.0};
    std::chrono::nanoseconds accepted_delta{0};
};

class FixedStepClock {
  public:
    static constexpr std::uint32_t default_tick_rate = 60U;
    static constexpr std::chrono::milliseconds default_max_frame_delta{100};

    explicit FixedStepClock(
        std::uint32_t tick_rate = default_tick_rate,
        std::chrono::nanoseconds max_frame_delta = default_max_frame_delta) noexcept;

    [[nodiscard]] FrameAdvance advance(std::chrono::nanoseconds frame_delta) noexcept;
    void reset() noexcept;

    [[nodiscard]] std::uint32_t tick_rate() const noexcept { return tick_rate_; }
    [[nodiscard]] std::uint64_t total_steps() const noexcept { return total_steps_; }
    [[nodiscard]] double logic_seconds() const noexcept;

  private:
    static constexpr std::uint64_t nanoseconds_per_second = 1'000'000'000ULL;

    std::uint32_t tick_rate_{default_tick_rate};
    std::chrono::nanoseconds max_frame_delta_{default_max_frame_delta};
    std::uint64_t scaled_remainder_{0U};
    std::uint64_t total_steps_{0U};
};

}  // namespace snake::core
