#include "snake/core/fixed_step.hpp"

#include <chrono>
#include <cmath>
#include <cstdlib>
#include <iostream>

namespace {

std::uint64_t simulate_one_second(const int display_frames) {
    using namespace std::chrono;
    snake::core::FixedStepClock clock;
    constexpr std::int64_t total_nanoseconds = 1'000'000'000LL;
    const std::int64_t base = total_nanoseconds / display_frames;
    const std::int64_t remainder = total_nanoseconds % display_frames;

    for (int frame = 0; frame < display_frames; ++frame) {
        const auto delta = nanoseconds(base + (frame < remainder ? 1 : 0));
        const auto result = clock.advance(delta);
        if (result.interpolation_alpha < 0.0 || result.interpolation_alpha >= 1.0) {
            return 0U;
        }
    }
    return clock.total_steps();
}

}  // namespace

int main() {
    for (const int display_frames : {30, 60, 90, 120}) {
        if (simulate_one_second(display_frames) != 60U) {
            std::cerr << "logic time changed with display frame rate\n";
            return EXIT_FAILURE;
        }
    }

    using namespace std::chrono_literals;
    snake::core::FixedStepClock clock;
    const auto clamped = clock.advance(500ms);
    if (clamped.accepted_delta != 100ms || clamped.simulation_steps != 6U) {
        std::cerr << "long background delta was not clamped\n";
        return EXIT_FAILURE;
    }

    static_cast<void>(clock.advance(8ms));
    clock.reset();
    const auto after_reset = clock.advance(1ms);
    if (after_reset.simulation_steps != 0U || after_reset.interpolation_alpha <= 0.0) {
        std::cerr << "reset did not clear interpolation remainder\n";
        return EXIT_FAILURE;
    }
    if (std::abs(clock.logic_seconds() - 0.1) > 1.0e-9) {
        std::cerr << "reset must not rewind completed logic time\n";
        return EXIT_FAILURE;
    }

    return EXIT_SUCCESS;
}
