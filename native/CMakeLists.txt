cmake_minimum_required(VERSION 3.22.1)

project(snake_evolution_arena LANGUAGES CXX)

option(SNAKE_BUILD_TESTS "Build host-side unit tests" OFF)

add_library(snake_core STATIC
    src/core/fixed_step.cpp
    src/core/version.cpp
)

target_include_directories(snake_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(snake_core PUBLIC cxx_std_17)
target_compile_options(snake_core PRIVATE -Wall -Wextra -Werror)
set_target_properties(snake_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    add_library(snake_engine SHARED
        ../app/src/main/cpp/jni_bridge.cpp
    )
    target_link_libraries(snake_engine PRIVATE snake_core android log)
    target_compile_options(snake_engine PRIVATE -Wall -Wextra -Werror)
else()
    if(SNAKE_BUILD_TESTS)
        enable_testing()
        add_executable(version_test tests/version_test.cpp)
        target_link_libraries(version_test PRIVATE snake_core)
        add_test(NAME version_test COMMAND version_test)

        add_executable(math_rng_test tests/math_rng_test.cpp)
        target_link_libraries(math_rng_test PRIVATE snake_core)
        add_test(NAME math_rng_test COMMAND math_rng_test)

        add_executable(fixed_step_test tests/fixed_step_test.cpp)
        target_link_libraries(fixed_step_test PRIVATE snake_core)
        add_test(NAME fixed_step_test COMMAND fixed_step_test)
    endif()
endif()
