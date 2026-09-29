[app]
# Application title
title = DE CAMERA

# Package name
package.name = decamera

# Package domain
package.domain = com.sari

# Application version
version = 1.0.0

# Requirements
requirements = python3,kivy,pillow,numpy,pyjnius,android

# Orientation
orientation = portrait

# Permissions
android.permissions = CAMERA,WRITE_EXTERNAL_STORAGE,READ_EXTERNAL_STORAGE,RECORD_AUDIO,ACCESS_FINE_LOCATION

# Features
android.features = android.hardware.camera,android.hardware.camera.autofocus

# Architecture
android.archs = arm64-v8a

# Android API levels
android.api = 31
android.minapi = 24
android.ndk = 25b
android.accept_sdk_license = True

# Buildozer settings
[buildozer]
log_level = 2
warn_on_root = 1
