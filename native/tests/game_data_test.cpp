#include "snake/data/game_data.hpp"

#include <cstdlib>
#include <iostream>
#include <set>
#include <string_view>

template <typename Definitions>
bool unique_non_empty_ids(const Definitions& definitions) {
    std::set<std::string_view> ids;
    for (const auto& definition : definitions) {
        if (definition.id.empty() || definition.name.empty() || !ids.insert(definition.id).second) {
            return false;
        }
    }
    return true;
}

int main() {
    if (!unique_non_empty_ids(snake::data::maps()) ||
        !unique_non_empty_ids(snake::data::modes()) ||
        !unique_non_empty_ids(snake::data::archetypes()) ||
        !unique_non_empty_ids(snake::data::skins())) {
        std::cerr << "game data contains an empty or duplicate id\n";
        return EXIT_FAILURE;
    }

    const std::set<std::string_view> expected_themes{"neon", "nature", "mutation", "mythic"};
    for (const auto theme : expected_themes) {
        int count = 0;
        for (const auto& skin : snake::data::skins()) {
            count += skin.theme == theme ? 1 : 0;
        }
        if (count != 3) {
            std::cerr << "each cosmetic theme must contain exactly three skins\n";
            return EXIT_FAILURE;
        }
    }
    return EXIT_SUCCESS;
}
