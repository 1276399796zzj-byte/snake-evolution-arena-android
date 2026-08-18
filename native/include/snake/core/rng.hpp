#pragma once

#include <cstdint>
#include <limits>

namespace snake::core {

class DeterministicRng {
  public:
    explicit DeterministicRng(const std::uint64_t seed, const std::uint64_t stream = 1U) noexcept {
        reseed(seed, stream);
    }

    void reseed(const std::uint64_t seed, const std::uint64_t stream = 1U) noexcept {
        state_ = 0U;
        increment_ = (stream << 1U) | 1U;
        static_cast<void>(next_u32());
        state_ += seed;
        static_cast<void>(next_u32());
    }

    [[nodiscard]] std::uint32_t next_u32() noexcept {
        const std::uint64_t old_state = state_;
        state_ = old_state * 6364136223846793005ULL + increment_;
        const auto xorshifted = static_cast<std::uint32_t>(((old_state >> 18U) ^ old_state) >> 27U);
        const auto rotation = static_cast<std::uint32_t>(old_state >> 59U);
        return (xorshifted >> rotation) | (xorshifted << ((0U - rotation) & 31U));
    }

    [[nodiscard]] float unit_float() noexcept {
        constexpr float divisor = static_cast<float>(std::uint64_t{1} << 32U);
        return static_cast<float>(next_u32()) / divisor;
    }

    [[nodiscard]] std::uint32_t bounded(const std::uint32_t upper_exclusive) noexcept {
        if (upper_exclusive == 0U) {
            return 0U;
        }
        const std::uint32_t threshold = (0U - upper_exclusive) % upper_exclusive;
        while (true) {
            const std::uint32_t value = next_u32();
            if (value >= threshold) {
                return value % upper_exclusive;
            }
        }
    }

    [[nodiscard]] std::uint64_t state() const noexcept { return state_; }

  private:
    std::uint64_t state_{0U};
    std::uint64_t increment_{1U};
};

}  // namespace snake::core
