cmake_minimum_required(VERSION 3.22.1)
project(deskforge_engine LANGUAGES CXX)

set(AUDIO_CONFIG_FILE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../config/audio/bridge.json")
file(READ "${AUDIO_CONFIG_FILE}" AUDIO_CONFIG_JSON)
string(JSON AUDIO_CONFIG_SCHEMA GET "${AUDIO_CONFIG_JSON}" schemaVersion)
if(NOT AUDIO_CONFIG_SCHEMA EQUAL 1)
    message(FATAL_ERROR "Unsupported DeskForge audio bridge schema")
endif()
string(JSON AUDIO_BUFFER_DURATION_MS GET "${AUDIO_CONFIG_JSON}" bufferDurationMs)
string(JSON AUDIO_IDLE_TIMEOUT_MS GET "${AUDIO_CONFIG_JSON}" playbackIdleTimeoutMs)
string(JSON AUDIO_PLAYBACK_FILE GET "${AUDIO_CONFIG_JSON}" playback fileName)
string(JSON AUDIO_PLAYBACK_RATE GET "${AUDIO_CONFIG_JSON}" playback sampleRateHz)
string(JSON AUDIO_PLAYBACK_CHANNELS GET "${AUDIO_CONFIG_JSON}" playback channels)
string(JSON AUDIO_MICROPHONE_FILE GET "${AUDIO_CONFIG_JSON}" microphone fileName)
string(JSON AUDIO_MICROPHONE_RATE GET "${AUDIO_CONFIG_JSON}" microphone sampleRateHz)
string(JSON AUDIO_MICROPHONE_CHANNELS GET "${AUDIO_CONFIG_JSON}" microphone channels)
configure_file(audio_config.h.in "${CMAKE_CURRENT_BINARY_DIR}/generated/audio_config.h" @ONLY)

add_library(
    deskforge_engine
    SHARED
    audio_bridge.cpp
    native_egl_presenter.cpp
    native_engine.cpp
    rfb_client.cpp
    rfb_clipboard.cpp
)
target_include_directories(deskforge_engine PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/generated")

target_compile_features(deskforge_engine PRIVATE cxx_std_20)
target_compile_options(deskforge_engine PRIVATE -fvisibility=hidden -fstack-protector-strong)
target_link_options(deskforge_engine PRIVATE -Wl,-z,relro,-z,now)

find_library(android_log_library log REQUIRED)
find_library(aaudio_library aaudio REQUIRED)
find_library(android_library android REQUIRED)
find_library(egl_library EGL REQUIRED)
find_library(gles_library GLESv2 REQUIRED)
find_library(zlib_library z REQUIRED)

target_link_libraries(
    deskforge_engine
    PRIVATE
    ${android_log_library}
    ${aaudio_library}
    ${android_library}
    ${egl_library}
    ${gles_library}
    ${zlib_library}
)
