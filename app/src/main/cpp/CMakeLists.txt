cmake_minimum_required(VERSION 3.22.1)

project("decamera")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    decamera
    SHARED
    main.cpp
    android/JniBridge.cpp
    gpu/GLESRenderer.cpp
    video/VideoEngine.cpp
)

target_include_directories(
    decamera
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

find_library(
    jnigraphics-lib
    jnigraphics
)

target_link_libraries(
    decamera
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    EGL
    GLESv2
)
