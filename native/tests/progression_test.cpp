#include "snake/game/world.hpp"

#include <cassert>

int main() {
    snake::game::WorldConfig config{};
    config.width = 600.0F;
    config.height = 400.0F;
    config.pickup_radius = 1000.0F;
    config.initial_food_count = 1U;
    config.bot_count = 0U;
    config.seed = 0x51A0ULL;
    snake::game::World world(config);

    for (int pickup = 0; pickup < 5; ++pickup) {
        static_cast<void>(world.step({{1.0F, 0.0F}, false}, 0.0F));
    }
    assert(world.level() == 1U);
    assert(!world.upgrade_pending());

    int total_pickups = 5;
    while (!world.upgrade_pending() && total_pickups < 45) {
        static_cast<void>(world.step({{1.0F, 0.0F}, false}, 0.0F));
        total_pickups += 1;
    }
    assert(world.upgrade_pending());
    assert(world.level() == 2U);
    assert(total_pickups > 10);
    assert(world.experience_required() > 40U);

    world.choose_upgrade(0U);
    assert(!world.upgrade_pending());
    return 0;
}
