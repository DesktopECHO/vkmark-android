cmake_minimum_required(VERSION 3.22.1)

project(vkmark-android C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(VKMARK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(VKMARK_SRC "${VKMARK_ROOT}/src")

if(NOT VKMARK_VERSION)
    set(VKMARK_VERSION "unknown")
endif()

include(FetchContent)

# The NDK provides the Vulkan C headers and library, but neither the C++
# bindings (vulkan.hpp) nor the registry (vk.xml), so get them from the
# upstream Vulkan-Headers release that matches the NDK headers.
FetchContent_Declare(
    vulkan_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
    GIT_TAG v1.3.275
    GIT_SHALLOW TRUE
    )
FetchContent_GetProperties(vulkan_headers)
if(NOT vulkan_headers_POPULATED)
    FetchContent_Populate(vulkan_headers)
endif()

FetchContent_Declare(
    glm
    GIT_REPOSITORY https://github.com/g-truc/glm.git
    GIT_TAG 1.0.1
    GIT_SHALLOW TRUE
    )
FetchContent_GetProperties(glm)
if(NOT glm_POPULATED)
    FetchContent_Populate(glm)
endif()

# Build a minimal static assimp with only the importers vkmark needs.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_ALL_IMPORTERS_BY_DEFAULT OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_3DS_IMPORTER ON CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_PLY_IMPORTER ON CACHE BOOL "" FORCE)
set(ASSIMP_NO_EXPORT ON CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_ASSIMP_TOOLS OFF CACHE BOOL "" FORCE)
set(ASSIMP_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
set(ASSIMP_INSTALL OFF CACHE BOOL "" FORCE)
set(ASSIMP_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)
set(ASSIMP_ANDROID_JNIIOSYSTEM OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    assimp
    GIT_REPOSITORY https://github.com/assimp/assimp.git
    GIT_TAG v5.4.3
    GIT_SHALLOW TRUE
    )
FetchContent_MakeAvailable(assimp)

find_package(Python3 REQUIRED COMPONENTS Interpreter)

set(FORMAT_MAP_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/gen")
set(FORMAT_MAP_GEN_H "${FORMAT_MAP_GEN_DIR}/format_map_gen.h")
file(MAKE_DIRECTORY "${FORMAT_MAP_GEN_DIR}")
add_custom_command(
    OUTPUT "${FORMAT_MAP_GEN_H}"
    COMMAND Python3::Interpreter "${VKMARK_SRC}/gen_format_map.py"
            "${vulkan_headers_SOURCE_DIR}/registry/vk.xml" "${FORMAT_MAP_GEN_H}"
    DEPENDS "${VKMARK_SRC}/gen_format_map.py"
            "${vulkan_headers_SOURCE_DIR}/registry/vk.xml"
    VERBATIM
    )

file(GLOB VKMARK_VKUTIL_SOURCES CONFIGURE_DEPENDS "${VKMARK_SRC}/vkutil/*.cpp")
file(GLOB VKMARK_SCENE_SOURCES CONFIGURE_DEPENDS "${VKMARK_SRC}/scenes/*.cpp")

add_library(
    vkmark-android SHARED
    vkmark_jni.cpp
    "${VKMARK_SRC}/benchmark.cpp"
    "${VKMARK_SRC}/benchmark_collection.cpp"
    "${VKMARK_SRC}/default_benchmarks.cpp"
    "${VKMARK_SRC}/device_uuid.cpp"
    "${VKMARK_SRC}/log.cpp"
    "${VKMARK_SRC}/main_loop.cpp"
    "${VKMARK_SRC}/mesh.cpp"
    "${VKMARK_SRC}/model.cpp"
    "${VKMARK_SRC}/options.cpp"
    "${VKMARK_SRC}/scene.cpp"
    "${VKMARK_SRC}/scene_collection.cpp"
    "${VKMARK_SRC}/util.cpp"
    "${VKMARK_SRC}/vulkan_state.cpp"
    "${VKMARK_SRC}/ws/swapchain_window_system.cpp"
    "${VKMARK_SRC}/ws/android_native_system.cpp"
    ${VKMARK_VKUTIL_SOURCES}
    ${VKMARK_SCENE_SOURCES}
    "${FORMAT_MAP_GEN_H}"
    )

target_include_directories(
    vkmark-android PRIVATE
    "${vulkan_headers_SOURCE_DIR}/include"
    "${glm_SOURCE_DIR}"
    "${VKMARK_SRC}"
    "${VKMARK_SRC}/ws"
    "${FORMAT_MAP_GEN_DIR}"
    )

target_compile_definitions(
    vkmark-android PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR
    VKMARK_VERSION_STR="${VKMARK_VERSION}"
    # Data files are read from the APK assets and the window system is
    # built in, so these are just placeholders.
    VKMARK_DATA_DIR="assets"
    VKMARK_WINDOW_SYSTEM_DIR=""
    )

target_link_libraries(vkmark-android PRIVATE assimp::assimp vulkan android log)
