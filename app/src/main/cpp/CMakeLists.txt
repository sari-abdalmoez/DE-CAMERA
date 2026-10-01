cmake_minimum_required(VERSION 3.22)
project(sari_camera)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED OpenCV_DIR)
  set(OpenCV_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/opencv/sdk/native/jni")
endif()
find_package(OpenCV REQUIRED COMPONENTS core imgproc photo features2d calib3d)

if(EXISTS "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libsari_astro.so")
  add_library(sari_astro SHARED IMPORTED)
  set_target_properties(sari_astro PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libsari_astro.so"
    IMPORTED_NO_SONAME TRUE
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/include"
  )
else()
  message(FATAL_ERROR "Rust astro library missing for ${ANDROID_ABI}. Build app/src/main/rust first.")
endif()

set(SARI_SOURCES sari_engine.cpp sari_jni.cpp rust_bridge.cpp sari_ncnn.cpp)
add_library(sari_camera SHARED ${SARI_SOURCES})
target_include_directories(sari_camera PRIVATE ${OpenCV_INCLUDE_DIRS} include)

set(SARI_HAS_NCNN OFF)
set(_NCNN_CONFIG "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/ncnn/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
if(EXISTS "${_NCNN_CONFIG}")
  set(ncnn_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/ncnn/${ANDROID_ABI}/lib/cmake/ncnn")
  find_package(ncnn REQUIRED)
  set(SARI_HAS_NCNN ON)
endif()

if(SARI_HAS_NCNN)
  target_compile_definitions(sari_camera PRIVATE SARI_HAS_NCNN=1)
  target_link_libraries(sari_camera ${OpenCV_LIBS} log android sari_astro ncnn)
else()
  target_link_libraries(sari_camera ${OpenCV_LIBS} log android sari_astro)
endif()

target_compile_options(sari_camera PRIVATE -O3 -fno-omit-frame-pointer -fno-exceptions -fno-rtti -ffunction-sections -fdata-sections)
