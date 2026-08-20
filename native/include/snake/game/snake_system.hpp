#pragma once

#include "snake/game/entities.hpp"

namespace snake::game {

struct WorldConfig;

namespace systems {

void advance_snake(Snake& snake, const InputState& input, const WorldConfig& config, float delta_seconds);

}  // namespace systems
}  // namespace snake::game
