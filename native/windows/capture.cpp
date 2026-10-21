#include "platform.hpp"
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <mmreg.h>
#include <ks.h>
#include <ksmedia.h>
#include <thread>
#include <future>

namespace {
std::thread capture;
std::atomic<bool> stopping{false};
void requireHr(HRESULT value) { if (FAILED(value)) throw value; }
void captureAudio(json options, std::shared_ptr<std::promise<HRESULT>> ready) {
    auto initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool announced = false;
    WAVEFORMATEX* format = nullptr;
    HANDLE event = nullptr;
    try {
        requireHr(initialized);
        ComPtr<IMMDeviceEnumerator> enumerator;
        requireHr(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator)));
        ComPtr<IMMDevice> device;
        auto id = options.value("deviceId", "");
        const bool loopback = options.value("source", "loopback") == "loopback";
        if (id.empty()) requireHr(enumerator->GetDefaultAudioEndpoint(loopback ? eRender : eCapture, eMultimedia, &device));
        else { auto value = wide(id); requireHr(enumerator->GetDevice(value.c_str(), &device)); }
        ComPtr<IAudioClient> client;
        requireHr(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)client.GetAddressOf()));
        requireHr(client->GetMixFormat(&format));
        if (format->nChannels < 1 || format->nChannels > 32) throw AUDCLNT_E_UNSUPPORTED_FORMAT;
        bool isFloat = format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT;
        if (format->wFormatTag == WAVE_FORMAT_EXTENSIBLE) {
            const auto& subformat = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(format)->SubFormat;
            isFloat = IsEqualGUID(subformat, KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
            if (!isFloat && !IsEqualGUID(subformat, KSDATAFORMAT_SUBTYPE_PCM)) throw AUDCLNT_E_UNSUPPORTED_FORMAT;
        } else if (!isFloat && format->wFormatTag != WAVE_FORMAT_PCM) throw AUDCLNT_E_UNSUPPORTED_FORMAT;
        if ((!isFloat && format->wBitsPerSample != 16 && format->wBitsPerSample != 24 && format->wBitsPerSample != 32) || (isFloat && format->wBitsPerSample != 32)) throw AUDCLNT_E_UNSUPPORTED_FORMAT;
        DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | (loopback ? AUDCLNT_STREAMFLAGS_LOOPBACK : 0);
        requireHr(client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 1000000, 0, format, nullptr));
        event = CreateEventW(nullptr, FALSE, FALSE, nullptr); if (!event) throw HRESULT_FROM_WIN32(GetLastError());
        requireHr(client->SetEventHandle(event));
        ComPtr<IAudioCaptureClient> source; requireHr(client->GetService(IID_PPV_ARGS(&source)));
        SharedPlane plane(options.at("buffer"));
        requireHr(client->Start()); ready->set_value(S_OK); announced = true;
        emit({{"event", "state"}, {"values", {{"state", "CAPTURING"}, {"sampleRate", format->nSamplesPerSec}, {"channels", format->nChannels}}}});
        std::vector<float> collected;
        const size_t block = options.value("fftSize", 2048) * format->nChannels;
        while (!stopping.load()) {
            if (WaitForSingleObject(event, 250) == WAIT_TIMEOUT) continue;
            UINT packet = 0; requireHr(source->GetNextPacketSize(&packet));
            while (packet > 0) {
                BYTE* bytes = nullptr; UINT frames = 0; DWORD status = 0;
                requireHr(source->GetBuffer(&bytes, &frames, &status, nullptr, nullptr));
                size_t count = (size_t)frames * format->nChannels;
                for (size_t i = 0; i < count; ++i) {
                    float value = 0;
                    if (!(status & AUDCLNT_BUFFERFLAGS_SILENT)) {
                        if (isFloat) value = reinterpret_cast<float*>(bytes)[i];
                        else if (format->wBitsPerSample == 16) value = reinterpret_cast<short*>(bytes)[i] / 32768.0f;
                        else if (format->wBitsPerSample == 32) value = reinterpret_cast<int*>(bytes)[i] / 2147483648.0f;
                        else { const auto ptr = bytes + i * 3; int raw = (ptr[0] | ptr[1] << 8 | ptr[2] << 16); if (raw & 0x800000) raw |= ~0xffffff; value = raw / 8388608.0f; }
                    }
                    collected.push_back(value);
                    if (collected.size() == block) { plane.publish(collected.data(), block * sizeof(float), 0, 0, format->nSamplesPerSec, format->nChannels); collected.clear(); }
                }
                requireHr(source->ReleaseBuffer(frames)); requireHr(source->GetNextPacketSize(&packet));
            }
        }
        client->Stop();
    } catch (HRESULT failure) {
        if (!announced) ready->set_value(failure);
        else emit({{"event", "state"}, {"values", {{"state", "FAILED"}, {"errorCode", "CAPTURE_FAILED"}}}});
    } catch (...) { if (!announced) ready->set_value(E_FAIL); }
    if (format) CoTaskMemFree(format); if (event) CloseHandle(event);
    if (SUCCEEDED(initialized)) CoUninitialize();
}
}
void stop_capture() { stopping = true; if (capture.joinable()) capture.join(); }
void dispatch_capture(const std::string& method, const json& args, Reply reply) {
    if (method != "open" || capture.joinable()) { reply({{"code", "OPERATION_UNSUPPORTED"}, {"error", "Unknown capture operation"}}); return; }
    auto ready = std::make_shared<std::promise<HRESULT>>(); auto result = ready->get_future();
    stopping = false; capture = std::thread(captureAudio, args, ready);
    HRESULT code = result.get();
    if (FAILED(code)) { stop_capture(); failed(reply, code == E_ACCESSDENIED ? "PERMISSION_DENIED" : "CAPTURE_UNAVAILABLE", code); }
    else reply(nullptr);
}
