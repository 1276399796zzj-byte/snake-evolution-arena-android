cmake_minimum_required(VERSION 3.22.1)

project(snake_evolution_arena LANGUAGES CXX)

option(SNAKE_BUILD_TESTS "Build host-side unit tests" OFF)

add_library(snake_core STATIC
    src/core/fixed_step.cpp
    src/core/version.cpp
    src/data/game_data.cpp
    src/game/collision_system.cpp
    src/game/snake_system.cpp
    src/game/world.cpp
    src/platform/engine_host.cpp
)

target_include_directories(snake_core
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(snake_core PUBLIC cxx_std_17)
target_compile_options(snake_core PRIVATE -Wall -Wextra -Werror)
set_target_properties(snake_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    if(WIN32)
        set(SNAKE_NDK_HOST_TAG windows-x86_64)
        set(SNAKE_GLSLC_NAME glslc.exe)
    elseif(APPLE)
        set(SNAKE_NDK_HOST_TAG darwin-x86_64)
        set(SNAKE_GLSLC_NAME glslc)
    else()
        set(SNAKE_NDK_HOST_TAG linux-x86_64)
        set(SNAKE_GLSLC_NAME glslc)
    endif()
    set(SNAKE_GLSLC
        "${CMAKE_ANDROID_NDK}/shader-tools/${SNAKE_NDK_HOST_TAG}/${SNAKE_GLSLC_NAME}"
    )
    if(NOT EXISTS "${SNAKE_GLSLC}")
        message(FATAL_ERROR "Android NDK glslc was not found at ${SNAKE_GLSLC}")
    endif()

    set(SNAKE_SHADER_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated/shaders")
    file(MAKE_DIRECTORY "${SNAKE_SHADER_OUTPUT_DIR}")
    add_custom_command(
        OUTPUT "${SNAKE_SHADER_OUTPUT_DIR}/arena.vert.inc"
        COMMAND "${SNAKE_GLSLC}" -O -mfmt=c
            "${CMAKE_CURRENT_SOURCE_DIR}/shaders/arena.vert"
            -o "${SNAKE_SHADER_OUTPUT_DIR}/arena.vert.inc"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/arena.vert"
        VERBATIM
    )
    add_custom_command(
        OUTPUT "${SNAKE_SHADER_OUTPUT_DIR}/arena.frag.inc"
        COMMAND "${SNAKE_GLSLC}" -O -mfmt=c
            "${CMAKE_CURRENT_SOURCE_DIR}/shaders/arena.frag"
            -o "${SNAKE_SHADER_OUTPUT_DIR}/arena.frag.inc"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/arena.frag"
        VERBATIM
    )
    add_custom_target(snake_vulkan_shaders DEPENDS
        "${SNAKE_SHADER_OUTPUT_DIR}/arena.vert.inc"
        "${SNAKE_SHADER_OUTPUT_DIR}/arena.frag.inc"
    )

    add_library(snake_engine SHARED
        ../app/src/main/cpp/jni_bridge.cpp
        src/render/vulkan_renderer.cpp
    )
    add_dependencies(snake_engine snake_vulkan_shaders)
    target_include_directories(snake_engine PRIVATE "${SNAKE_SHADER_OUTPUT_DIR}")
    target_link_libraries(snake_engine PRIVATE snake_core android log vulkan)
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

        add_executable(game_data_test tests/game_data_test.cpp)
        target_link_libraries(game_data_test PRIVATE snake_core)
        add_test(NAME game_data_test COMMAND game_data_test)

        add_executable(world_movement_test tests/world_movement_test.cpp)
        target_link_libraries(world_movement_test PRIVATE snake_core)
        add_test(NAME world_movement_test COMMAND world_movement_test)

        add_executable(collision_pickup_test tests/collision_pickup_test.cpp)
        target_link_libraries(collision_pickup_test PRIVATE snake_core)
        add_test(NAME collision_pickup_test COMMAND collision_pickup_test)

        add_executable(engine_host_test tests/engine_host_test.cpp)
        target_link_libraries(engine_host_test PRIVATE snake_core)
        add_test(NAME engine_host_test COMMAND engine_host_test)

        add_executable(progression_test tests/progression_test.cpp)
        target_link_libraries(progression_test PRIVATE snake_core)
        add_test(NAME progression_test COMMAND progression_test)

        add_executable(ability_test tests/ability_test.cpp)
        target_link_libraries(ability_test PRIVATE snake_core)
        add_test(NAME ability_test COMMAND ability_test)
    endif()
endif()
