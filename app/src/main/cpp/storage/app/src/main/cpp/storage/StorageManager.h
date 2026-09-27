#pragma once
#include "image/ImageBuffer.h"
#include <string>
#include <vector>
#include <cstdint>
#include <mutex>

struct JavaVM;

namespace decamera {

class StorageManager {
public:
    static StorageManager& instance();
    void setJavaVM(JavaVM* vm) { vm_ = vm; }

    std::string saveJpeg(const ImageBuffer& rgba, int quality);
    std::string saveDng(const std::vector<uint8_t>& fileWithLen);
    std::string externalMoviesDir();
    void scanFile(const std::string& path);

private:
    JavaVM* vm_ = nullptr;
    std::mutex mu_;
};

} // namespace decamera
