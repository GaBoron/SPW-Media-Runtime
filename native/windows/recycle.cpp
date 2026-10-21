#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shobjidl.h>
#include <shellapi.h>
#include <filesystem>

// One exact absolute target. No UI, elevation prompt, or permanent-delete fallback.
int wmain(int argc, wchar_t** argv) {
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX);
    try {
    if (argc != 2) return 2;
    const std::filesystem::path target(argv[1]);
    if (!target.is_absolute() || target == target.root_path() || target.filename().empty()) return 2;
    if (!std::filesystem::exists(target)) return 0;
    const auto initialized = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(initialized)) return 3;
    IFileOperation* operation = nullptr;
    IShellItem* item = nullptr;
    auto result = CoCreateInstance(CLSID_FileOperation, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&operation));
    if (SUCCEEDED(result)) result = operation->SetOperationFlags(FOF_SILENT | FOF_NOCONFIRMATION | FOF_NOERRORUI | FOFX_RECYCLEONDELETE | FOFX_EARLYFAILURE | FOFX_NOCOPYHOOKS);
    if (SUCCEEDED(result)) result = SHCreateItemFromParsingName(target.c_str(), nullptr, IID_PPV_ARGS(&item));
    if (SUCCEEDED(result)) result = operation->DeleteItem(item, nullptr);
    if (SUCCEEDED(result)) result = operation->PerformOperations();
    BOOL aborted = TRUE;
    if (SUCCEEDED(result)) result = operation->GetAnyOperationsAborted(&aborted);
    if (item) item->Release();
    if (operation) operation->Release();
    CoUninitialize();
    return SUCCEEDED(result) && !aborted && !std::filesystem::exists(target) ? 0 : 4;
    } catch (...) { return 4; }
}
