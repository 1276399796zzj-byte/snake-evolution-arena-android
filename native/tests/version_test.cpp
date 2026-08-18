#include "snake/core/version.hpp"

#include <cstdlib>
#include <iostream>

int main() {
    if (snake::core::version().empty()) {
        std::cerr << "core version must not be empty\n";
        return EXIT_FAILURE;
    }
    if (snake::core::save_format_version() <= 0) {
        std::cerr << "save format version must be positive\n";
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
