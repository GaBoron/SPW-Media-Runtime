#include "platform.hpp"
#include <iostream>
#include <deque>
#include <thread>
#include <cstring>

std::wstring wide(const std::string& value) {
    if (value.empty()) return {};
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), (int)value.size(), nullptr, 0);
    if (!length) throw std::runtime_error("Invalid UTF-8");
    std::wstring result(length, L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), (int)value.size(), result.data(), length);
    return result;
}
std::string utf8(const wchar_t* value) {
    if (!value) return {};
    int length = WideCharToMultiByte(CP_UTF8, 0, value, -1, nullptr, 0, nullptr, nullptr);
    std::string result(length, '\0');
    WideCharToMultiByte(CP_UTF8, 0, value, -1, result.data(), length, nullptr, nullptr);
    result.resize(length ? length - 1 : 0); return result;
}
void emit(const json& value) {
    static std::mutex output;
    std::lock_guard<std::mutex> lock(output);
    std::cout << value.dump() << '\n' << std::flush;
}
void failed(const Reply& reply, const char* code, HRESULT error) {
    reply(json{{"code", code}, {"error", std::string(code) + " (HRESULT " + std::to_string((long)error) + ")"}});
}
SharedPlane::SharedPlane(const json& descriptor) {
    capacity_ = descriptor.at("capacity").get<size_t>();
    auto path = wide(descriptor.at("path").get<std::string>());
    file_ = CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr);
    if (file_ == INVALID_HANDLE_VALUE) throw std::runtime_error("Cannot open shared buffer");
    mapping_ = CreateFileMappingW(file_, nullptr, PAGE_READWRITE, 0, 0, nullptr);
    if (mapping_) data_ = (unsigned char*)MapViewOfFile(mapping_, FILE_MAP_ALL_ACCESS, 0, 0, capacity_ + 64);
    if (!data_) { if (mapping_) CloseHandle(mapping_); CloseHandle(file_); throw std::runtime_error("Cannot map shared buffer"); }
}
SharedPlane::~SharedPlane() { if (data_) UnmapViewOfFile(data_); if (mapping_) CloseHandle(mapping_); if (file_ != INVALID_HANDLE_VALUE) CloseHandle(file_); }
void SharedPlane::publish(const void* bytes, size_t size, int width, int height, int rate, int channels) {
    if (size > capacity_) return;
    auto sequence = reinterpret_cast<volatile LONG64*>(data_ + 8);
    InterlockedIncrement64(sequence); MemoryBarrier();
    auto header = reinterpret_cast<int*>(data_);
    header[4] = (int)size; header[5] = width; header[6] = height; header[7] = rate; header[8] = channels;
    std::memcpy(data_ + 64, bytes, size); MemoryBarrier(); InterlockedIncrement64(sequence);
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    const std::string mode = argv[1];
    if (mode == "web-status") return web_status();
    HRESULT initialized = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(initialized)) return 3;
    const DWORD mainThread = GetCurrentThreadId();
    MSG message{}; PeekMessageW(&message, nullptr, 0, 0, PM_NOREMOVE);
    std::mutex mutex; std::deque<json> queue;
    std::thread input([&] {
        std::string line;
        while (std::getline(std::cin, line)) {
            try { auto request = json::parse(line); { std::lock_guard<std::mutex> lock(mutex); queue.push_back(std::move(request)); } PostThreadMessageW(mainThread, WM_APP, 0, 0); }
            catch (...) { emit({{"id", 0}, {"code", "INVALID_ARGUMENT"}, {"error", "Invalid request"}}); }
        }
        PostThreadMessageW(mainThread, WM_QUIT, 0, 0);
    });
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        if (message.message == WM_APP) {
            std::deque<json> requests; { std::lock_guard<std::mutex> lock(mutex); requests.swap(queue); }
            for (const auto& request : requests) {
                const auto id = request.at("id");
                Reply reply = [id](json result) { if (result.is_object() && result.contains("error")) { result["id"] = id; emit(result); } else emit({{"id", id}, {"result", result}}); };
                try {
                    auto method = request.at("method").get<std::string>();
                    if (mode == "web") dispatch_web(method, request.at("arguments"), reply);
                    else if (mode == "capture") dispatch_capture(method, request.at("arguments"), reply);
                    else reply({{"code", "OPERATION_UNSUPPORTED"}, {"error", "Unknown backend"}});
                } catch (...) { reply({{"code", "INVALID_ARGUMENT"}, {"error", "Backend rejected arguments"}}); }
            }
        } else { TranslateMessage(&message); DispatchMessageW(&message); }
    }
    stop_capture(); input.join(); CoUninitialize(); return 0;
}
