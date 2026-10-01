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
add_library(sari_camera SHARED sari_engine.cpp sari_jni.cpp rust_bridge.cpp)
target_include_directories(sari_camera PRIVATE ${OpenCV_INCLUDE_DIRS} include)
target_link_libraries(sari_camera ${OpenCV_LIBS} log android sari_astro)
target_compile_options(sari_camera PRIVATE -O3 -fno-omit-frame-pointer)
