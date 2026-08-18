#include "snake/core/version.hpp"

namespace snake::core {

std::string_view version() noexcept {
    return "0.1.0-native";
}

int save_format_version() noexcept {
    return 1;
}

}  // namespace snake::core
