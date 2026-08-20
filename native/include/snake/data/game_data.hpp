#pragma once

#include <array>
#include <string_view>

namespace snake::data {

struct NamedId {
    std::string_view id;
    std::string_view name;
};

struct SkinDefinition {
    std::string_view id;
    std::string_view name;
    std::string_view theme;
};

using MapDefinitions = std::array<NamedId, 3>;
using ModeDefinitions = std::array<NamedId, 3>;
using ArchetypeDefinitions = std::array<NamedId, 4>;
using SkinDefinitions = std::array<SkinDefinition, 12>;

[[nodiscard]] const MapDefinitions& maps() noexcept;
[[nodiscard]] const ModeDefinitions& modes() noexcept;
[[nodiscard]] const ArchetypeDefinitions& archetypes() noexcept;
[[nodiscard]] const SkinDefinitions& skins() noexcept;

}  // namespace snake::data
