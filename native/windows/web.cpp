#include "platform.hpp"
#include <WebView2.h>
#include <regex>
#include <algorithm>

namespace {
ComPtr<ICoreWebView2Environment> environment;
ComPtr<ICoreWebView2Controller> controller;
ComPtr<ICoreWebView2> view;
HWND window = nullptr;
std::vector<std::string> origins;
std::vector<json> messages;
std::string currentUrl;
bool loading = true;
std::string origin(const std::string& uri) {
    std::smatch match;
    if (!std::regex_search(uri, match, std::regex("^https?://[^/?#]+", std::regex::icase))) return {};
    auto value = match.str(); std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) { return (char)std::tolower(c); });
    if (value.find('@') != std::string::npos) return {};
    if (value.rfind("https://", 0) == 0 && value.size() > 4 && value.substr(value.size() - 4) == ":443") value.resize(value.size() - 4);
    if (value.rfind("http://", 0) == 0 && value.size() > 3 && value.substr(value.size() - 3) == ":80") value.resize(value.size() - 3);
    return value;
}
LRESULT CALLBACK windowProc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_SIZE && controller) { RECT bounds{}; GetClientRect(hwnd, &bounds); controller->put_Bounds(bounds); return 0; }
    if (message == WM_CLOSE) { ShowWindow(hwnd, SW_HIDE); return 0; }
    return DefWindowProcW(hwnd, message, wparam, lparam);
}
void state(const char* status, const std::string& failure = "") {
    emit({{"event", "state"}, {"values", {{"state", status}, {"url", currentUrl}, {"loading", loading}, {"errorCode", failure}}}});
}
void configure(const json& options, const Reply& reply) {
    EventRegistrationToken token{};
    view->add_NavigationStarting(Callback<ICoreWebView2NavigationStartingEventHandler>([](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT {
        LPWSTR uri = nullptr; args->get_Uri(&uri); currentUrl = utf8(uri); CoTaskMemFree(uri); loading = true; state("LOADING"); return S_OK;
    }).Get(), &token);
    view->add_NavigationCompleted(Callback<ICoreWebView2NavigationCompletedEventHandler>([](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
        BOOL success = FALSE; args->get_IsSuccess(&success); loading = false; state(success ? "READY" : "FAILED", success ? "" : "NAVIGATION_FAILED"); return S_OK;
    }).Get(), &token);
    view->add_ProcessFailed(Callback<ICoreWebView2ProcessFailedEventHandler>([](ICoreWebView2*, ICoreWebView2ProcessFailedEventArgs*) -> HRESULT { state("FAILED", "BROWSER_PROCESS_FAILED"); return S_OK; }).Get(), &token);
    view->add_WebMessageReceived(Callback<ICoreWebView2WebMessageReceivedEventHandler>([](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
        LPWSTR source = nullptr; args->get_Source(&source); auto sourceOrigin = origin(utf8(source)); CoTaskMemFree(source);
        if (sourceOrigin.empty() || std::find(origins.begin(), origins.end(), sourceOrigin) == origins.end()) return S_OK;
        LPWSTR value = nullptr; args->get_WebMessageAsJson(&value);
        try { if (messages.size() == 128) messages.erase(messages.begin()); messages.push_back({{"origin", sourceOrigin}, {"message", json::parse(utf8(value))}}); } catch (...) {}
        CoTaskMemFree(value); return S_OK;
    }).Get(), &token);
    for (auto& value : options.value("allowedOrigins", json::array())) { auto parsed = origin(value.get<std::string>()); if (!parsed.empty()) origins.push_back(parsed); }
    auto url = wide(options.value("url", "about:blank"));
    HRESULT result = view->Navigate(url.c_str());
    if (FAILED(result)) failed(reply, "NAVIGATION_FAILED", result); else reply(nullptr);
}
ComPtr<ICoreWebView2CookieManager> cookieManager() {
    ComPtr<ICoreWebView2_2> v2; if (FAILED(view.As(&v2))) throw std::runtime_error("Cookie API unavailable");
    ComPtr<ICoreWebView2CookieManager> manager; v2->get_CookieManager(&manager); return manager;
}
}

int web_status() {
    LPWSTR version = nullptr;
    HRESULT result = GetAvailableCoreWebView2BrowserVersionString(nullptr, &version);
    CoTaskMemFree(version);
    return SUCCEEDED(result) ? 0 : 1;
}

void dispatch_web(const std::string& method, const json& args, Reply reply) {
    if (method == "open") {
        if (view) throw std::runtime_error("Already open");
        LPWSTR version = nullptr;
        HRESULT available = GetAvailableCoreWebView2BrowserVersionString(nullptr, &version);
        CoTaskMemFree(version);
        if (FAILED(available)) { failed(reply, "DEPENDENCY_MISSING", available); return; }
        WNDCLASSW wc{}; wc.lpfnWndProc = windowProc; wc.hInstance = GetModuleHandleW(nullptr); wc.lpszClassName = L"SpwMediaWebView"; RegisterClassW(&wc);
        HWND parent = reinterpret_cast<HWND>(args.value("parentWindow", 0ULL));
        if (parent && !IsWindow(parent)) throw std::runtime_error("Parent HWND is invalid");
        window = CreateWindowExW(0, wc.lpszClassName, L"SPW Media", (parent ? WS_CHILD : WS_OVERLAPPEDWINDOW) | WS_VISIBLE, parent ? 0 : CW_USEDEFAULT, parent ? 0 : CW_USEDEFAULT, args.value("width", 960), args.value("height", 640), parent, nullptr, wc.hInstance, nullptr);
        if (!window) { failed(reply, "RENDER_FAILED", HRESULT_FROM_WIN32(GetLastError())); return; }
        auto profile = wide(args.at("profilePath").get<std::string>());
        HRESULT started = CreateCoreWebView2EnvironmentWithOptions(nullptr, profile.c_str(), nullptr, Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>([args, reply](HRESULT result, ICoreWebView2Environment* env) -> HRESULT {
            if (FAILED(result) || !env) { failed(reply, "INITIALIZATION_FAILED", result); return S_OK; }
            environment = env;
            auto started = environment->CreateCoreWebView2Controller(window, Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>([args, reply](HRESULT result, ICoreWebView2Controller* created) -> HRESULT {
                if (FAILED(result) || !created) { failed(reply, "INITIALIZATION_FAILED", result); return S_OK; }
                controller = created; controller->get_CoreWebView2(&view);
                RECT bounds{}; GetClientRect(window, &bounds); controller->put_Bounds(bounds); controller->put_IsVisible(TRUE);
                configure(args, reply); return S_OK;
            }).Get());
            if (FAILED(started)) failed(reply, "INITIALIZATION_FAILED", started); return S_OK;
        }).Get());
        if (FAILED(started)) failed(reply, "INITIALIZATION_FAILED", started);
        return;
    }
    if (!view) throw std::runtime_error("Browser not ready");
    if (method == "navigate") { auto uri = wide(args.at("url")); auto result = view->Navigate(uri.c_str()); if (FAILED(result)) failed(reply, "NAVIGATION_FAILED", result); else reply(nullptr); }
    else if (method == "evaluate") {
        auto script = wide(args.at("script"));
        auto result = view->ExecuteScript(script.c_str(), Callback<ICoreWebView2ExecuteScriptCompletedHandler>([reply](HRESULT code, LPCWSTR value) -> HRESULT {
            if (FAILED(code)) failed(reply, "SCRIPT_FAILED", code); else { try { reply(json::parse(utf8(value))); } catch (...) { reply(nullptr); } } return S_OK;
        }).Get());
        if (FAILED(result)) failed(reply, "SCRIPT_FAILED", result);
    } else if (method == "postMessage") {
        auto value = wide(args.at("message").dump()); auto result = view->PostWebMessageAsJson(value.c_str()); if (FAILED(result)) failed(reply, "MESSAGE_FAILED", result); else reply(nullptr);
    } else if (method == "resize") { SetWindowPos(window, nullptr, 0, 0, args.at("width"), args.at("height"), SWP_NOMOVE | SWP_NOZORDER); reply(nullptr); }
    else if (method == "messages") { reply(messages); messages.clear(); }
    else if (method == "cookies") {
        auto manager = cookieManager(); auto uri = wide(args.value("uri", ""));
        auto started = manager->GetCookies(uri.c_str(), Callback<ICoreWebView2GetCookiesCompletedHandler>([reply](HRESULT result, ICoreWebView2CookieList* cookies) -> HRESULT {
            if (FAILED(result)) { failed(reply, "COOKIE_FAILED", result); return S_OK; }
            UINT count = 0; cookies->get_Count(&count); auto values = json::array();
            for (UINT i = 0; i < count; ++i) {
                ComPtr<ICoreWebView2Cookie> cookie; cookies->GetValueAtIndex(i, &cookie);
                LPWSTR name=nullptr, value=nullptr, domain=nullptr, path=nullptr; BOOL secure=FALSE, http=FALSE; double expires=-1;
                cookie->get_Name(&name); cookie->get_Value(&value); cookie->get_Domain(&domain); cookie->get_Path(&path); cookie->get_IsSecure(&secure); cookie->get_IsHttpOnly(&http); cookie->get_Expires(&expires);
                values.push_back({{"name", utf8(name)}, {"value", utf8(value)}, {"domain", utf8(domain)}, {"path", utf8(path)}, {"secure", secure != FALSE}, {"httpOnly", http != FALSE}, {"expiresUnixSeconds", expires}});
                CoTaskMemFree(name); CoTaskMemFree(value); CoTaskMemFree(domain); CoTaskMemFree(path);
            }
            reply(values); return S_OK;
        }).Get());
        if (FAILED(started)) failed(reply, "COOKIE_FAILED", started);
    } else if (method == "setCookie") {
        auto manager = cookieManager(); ComPtr<ICoreWebView2Cookie> cookie;
        auto name=wide(args.at("name")), value=wide(args.at("value")), domain=wide(args.at("domain")), path=wide(args.value("path", "/"));
        auto result = manager->CreateCookie(name.c_str(), value.c_str(), domain.c_str(), path.c_str(), &cookie);
        if (FAILED(result)) { failed(reply, "COOKIE_FAILED", result); return; }
        cookie->put_IsSecure(args.value("secure", false)); cookie->put_IsHttpOnly(args.value("httpOnly", false)); cookie->put_Expires(args.value("expiresUnixSeconds", -1.0));
        result = manager->AddOrUpdateCookie(cookie.Get()); if (FAILED(result)) failed(reply, "COOKIE_FAILED", result); else reply(nullptr);
    } else if (method == "clearData") {
        ComPtr<ICoreWebView2_13> v13; ComPtr<ICoreWebView2Profile> profile; ComPtr<ICoreWebView2Profile2> profile2;
        if (FAILED(view.As(&v13)) || FAILED(v13->get_Profile(&profile)) || FAILED(profile.As(&profile2))) { failed(reply, "OPERATION_UNSUPPORTED", E_NOINTERFACE); return; }
        auto result = profile2->ClearBrowsingDataAll(Callback<ICoreWebView2ClearBrowsingDataCompletedHandler>([reply](HRESULT result) -> HRESULT { if (FAILED(result)) failed(reply, "CLEAR_DATA_FAILED", result); else reply(nullptr); return S_OK; }).Get());
        if (FAILED(result)) failed(reply, "CLEAR_DATA_FAILED", result);
    } else reply({{"code", "OPERATION_UNSUPPORTED"}, {"error", "Unknown browser operation"}});
}
