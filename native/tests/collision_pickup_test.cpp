#include "snake/game/collision_system.hpp"
#include "snake/game/world.hpp"

#include <cstdlib>
#include <iostream>
#include <vector>

int main() {
    const auto clamped = snake::game::collision::clamp_to_arena({-20.0F, 900.0F}, 500.0F, 400.0F, 10.0F);
    if (clamped.x != 10.0F || clamped.y != 390.0F) {
        std::cerr << "arena clamp failed\n";
        return EXIT_FAILURE;
    }
    if (!snake::game::collision::circles_overlap({0.0F, 0.0F}, 4.0F, {7.0F, 0.0F}, 3.0F) ||
        snake::game::collision::circles_overlap({0.0F, 0.0F}, 4.0F, {8.0F, 0.0F}, 3.0F)) {
        std::cerr << "circle collision boundary failed\n";
        return EXIT_FAILURE;
    }

    snake::game::WorldConfig config{};
    config.width = 1000.0F;
    config.height = 500.0F;
    config.initial_food_count = 0U;
    std::vector<snake::game::Food> foods{{7U, {500.0F, 250.0F}, 3U}};
    snake::game::World world(config, foods);

    const auto first = world.step({{1.0F, 0.0F}, false}, 0.0F);
    if (first.collected_food != 1U || first.score_delta != 3U || world.player().score != 3U) {
        std::cerr << "food pickup did not apply exactly once\n";
        return EXIT_FAILURE;
    }
    const auto second = world.step({{1.0F, 0.0F}, false}, 0.0F);
    if (second.collected_food != 0U || world.player().score != 3U) {
        std::cerr << "respawned food was collected twice\n";
        return EXIT_FAILURE;
    }

    return EXIT_SUCCESS;
}
