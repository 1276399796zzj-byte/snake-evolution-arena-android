#include "snake/game/world.hpp"

#include <cassert>

int main() {
    snake::game::WorldConfig config{};
    config.width = 320.0F;
    config.height = 180.0F;
    config.initial_food_count = 4U;
    config.bot_count = 0U;
    config.seed = 0xAB1A17ULL;
    snake::game::World world(config);

    const std::uint32_t score_before_pulse = world.player().score;
    world.activate_ability(0U);
    assert(world.player().score > score_before_pulse);
    assert(world.experience() > 0U);

    world.activate_ability(2U);
    assert(world.shield_remaining_seconds() >= 3.19F);
    static_cast<void>(world.step({{1.0F, 0.0F}, false}, 0.5F));
    assert(world.shield_remaining_seconds() < 3.0F);
    assert(world.shield_remaining_seconds() > 2.5F);

    return 0;
}
