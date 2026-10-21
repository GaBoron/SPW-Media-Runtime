#pragma once
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wrl.h>
#include <nlohmann/json.hpp>
#include <string>
#include <memory>
#include <functional>
#include <mutex>
#include <atomic>
#include <vector>

using json = nlohmann::json;
using Microsoft::WRL::ComPtr;
using Microsoft::WRL::Callback;
using Reply = std::function<void(json)>;

std::wstring wide(const std::string& value);
std::string utf8(const wchar_t* value);
void emit(const json& value);
void failed(const Reply& reply, const char* code, HRESULT error);
void dispatch_web(const std::string& method, const json& args, Reply reply);
int web_status();
void dispatch_capture(const std::string& method, const json& args, Reply reply);
void stop_capture();

class SharedPlane {
    HANDLE file_ = INVALID_HANDLE_VALUE;
    HANDLE mapping_ = nullptr;
    unsigned char* data_ = nullptr;
    size_t capacity_ = 0;
public:
    explicit SharedPlane(const json& descriptor);
    ~SharedPlane();
    void publish(const void* bytes, size_t size, int width, int height, int rate, int channels);
};
