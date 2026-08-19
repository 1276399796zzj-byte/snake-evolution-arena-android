#include "snake/data/game_data.hpp"

namespace snake::data {
namespace {

constexpr MapDefinitions map_definitions{{
    {"neon", "霓虹竞技场"},
    {"wilds", "幻彩秘境"},
    {"lab", "异变实验室"},
}};

constexpr ModeDefinitions mode_definitions{{
    {"blitz", "3分钟闪击"},
    {"expedition", "异域远征"},
    {"endless", "无尽猎场"},
}};

constexpr ArchetypeDefinitions archetype_definitions{{
    {"viper", "影牙"},
    {"bulwark", "玄甲"},
    {"oracle", "星谕"},
    {"scavenger", "拾荒者"},
}};

constexpr SkinDefinitions skin_definitions{{
    {"neon-pulse", "霓虹脉冲", "neon"},
    {"red-overclock", "赤曜超频", "neon"},
    {"quantum-ghost", "量子幽影", "neon"},
    {"prism-corolla", "棱镜花冠", "nature"},
    {"emerald-vine", "翡翠藤甲", "nature"},
    {"abyss-glow", "深海荧光", "nature"},
    {"acid-zero", "酸蚀零号", "mutation"},
    {"titan-crystal", "钛晶机械", "mutation"},
    {"fission-spore", "裂变孢体", "mutation"},
    {"azure-dragon", "青龙星轨", "mythic"},
    {"black-tortoise", "玄武幽甲", "mythic"},
    {"golden-serpent", "曜金天蛇", "mythic"},
}};

}  // namespace

const MapDefinitions& maps() noexcept { return map_definitions; }
const ModeDefinitions& modes() noexcept { return mode_definitions; }
const ArchetypeDefinitions& archetypes() noexcept { return archetype_definitions; }
const SkinDefinitions& skins() noexcept { return skin_definitions; }

}  // namespace snake::data
