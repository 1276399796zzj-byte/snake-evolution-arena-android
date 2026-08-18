#pragma once

#include <string_view>

namespace snake::core {

[[nodiscard]] std::string_view version() noexcept;
[[nodiscard]] int save_format_version() noexcept;

}  // namespace snake::core
