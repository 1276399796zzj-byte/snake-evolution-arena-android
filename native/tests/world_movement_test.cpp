#include "snake/core/fixed_step.hpp"
#include "snake/game/world.hpp"

#include <chrono>
#include <cmath>
#include <cstdlib>
#include <iostream>

namespace {

snake::game::World simulate(const int display_frames) {
    snake::game::WorldConfig config{};
    config.width = 2000.0F;
    config.height = 1000.0F;
    config.boundary_margin = 0.0F;
    config.base_speed = 120.0F;
    config.turn_response = 100.0F;
    config.initial_food_count = 0U;
    snake::game::World world(config);
    snake::core::FixedStepClock clock;

    constexpr std::int64_t total_nanoseconds = 1'000'000'000LL;
    const std::int64_t base = total_nanoseconds / display_frames;
    const std::int64_t remainder = total_nanoseconds % display_frames;
    for (int frame = 0; frame < display_frames; ++frame) {
        const auto advance = clock.advance(
            std::chrono::nanoseconds(base + (frame < remainder ? 1 : 0)));
        for (std::uint32_t step = 0; step < advance.simulation_steps; ++step) {
            static_cast<void>(world.step({{1.0F, 0.0F}, false}, 1.0F / 60.0F));
        }
    }
    return world;
}

}  // namespace

int main() {
    const auto at_30 = simulate(30);
    const auto at_120 = simulate(120);
    if (std::abs(at_30.player().position.x - at_120.player().position.x) > 1.0e-4F ||
        std::abs(at_30.player().position.y - at_120.player().position.y) > 1.0e-4F) {
        std::cerr << "display frame rate changed simulation position\n";
        return EXIT_FAILURE;
    }

    if (std::abs(at_30.player().position.x - 1120.0F) > 0.01F) {
        std::cerr << "base movement speed is incorrect\n";
        return EXIT_FAILURE;
    }

    snake::game::WorldConfig boost_config{};
    boost_config.width = 2000.0F;
    boost_config.height = 1000.0F;
    boost_config.boundary_margin = 10.0F;
    boost_config.base_speed = 100.0F;
    boost_config.initial_food_count = 0U;
    snake::game::World boosted(boost_config);
    for (int step = 0; step < 60; ++step) {
        static_cast<void>(boosted.step({{1.0F, 0.0F}, true}, 1.0F / 60.0F));
    }
    if (boosted.player().position.x <= 1100.0F || boosted.player().boost_energy >= 100.0F) {
        std::cerr << "boost did not increase speed and consume energy\n";
        return EXIT_FAILURE;
    }

    return EXIT_SUCCESS;
}
