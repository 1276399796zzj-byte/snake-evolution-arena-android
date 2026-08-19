#include "snake/platform/engine_host.hpp"

#include <array>
#include <cassert>
#include <cstddef>
#include <cstdint>

int main() {
    snake::platform::EngineHost host(1920.0F, 1080.0F, 0xA11CEULL);
    std::array<float, 1024> snapshot{};

    const std::size_t initial_size = host.write_snapshot(snapshot.data(), snapshot.size());
    assert(initial_size > 6U);
    assert(snapshot[4] >= 18.0F);
    assert(snapshot[5] >= 1.0F);
    const float initial_x = snapshot[0];

    constexpr std::int64_t start = 1'000'000'000LL;
    host.advance(start, {{1.0F, 0.0F}, false});
    for (std::int64_t frame = 1; frame <= 120; ++frame) {
        host.advance(start + frame * 8'333'333LL, {{1.0F, 0.0F}, false});
    }
    const std::size_t advanced_size = host.write_snapshot(snapshot.data(), snapshot.size());
    assert(advanced_size == initial_size);
    assert(snapshot[0] > initial_x);
    assert(snapshot[2] >= 99.0F);

    std::array<float, 5> undersized{};
    assert(host.write_snapshot(undersized.data(), undersized.size()) == 0U);
    assert(host.write_snapshot(nullptr, snapshot.size()) == 0U);
    return 0;
}
