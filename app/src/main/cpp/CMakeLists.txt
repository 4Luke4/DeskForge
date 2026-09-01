cmake_minimum_required(VERSION 3.22.1)
project(deskforge_engine LANGUAGES CXX)

add_library(deskforge_engine SHARED native_engine.cpp rfb_client.cpp rfb_clipboard.cpp)

target_compile_features(deskforge_engine PRIVATE cxx_std_20)
target_compile_options(deskforge_engine PRIVATE -fvisibility=hidden -fstack-protector-strong)
target_link_options(deskforge_engine PRIVATE -Wl,-z,relro,-z,now)

find_library(android_log_library log REQUIRED)
find_library(dynamic_loader_library dl REQUIRED)
find_library(aaudio_library aaudio REQUIRED)
find_library(android_library android REQUIRED)
find_library(zlib_library z REQUIRED)

target_link_libraries(
    deskforge_engine
    PRIVATE
    ${android_log_library}
    ${dynamic_loader_library}
    ${aaudio_library}
    ${android_library}
    ${zlib_library}
)
