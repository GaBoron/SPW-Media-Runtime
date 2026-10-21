#include <jni.h>
#include <cstdio>
#include <filesystem>
#include <string>
#include <vector>
#include <codecvt>
#include <locale>
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <dlfcn.h>
#endif

// A fixed media-worker entry point, in its own process, using the host's runtime image.
// jpackage runtime images need not contain a java executable.
using CreateVM = jint (JNICALL *)(JavaVM**, void**, void*);

static jstring javaText(JNIEnv* env, const std::u16string& text) {
    return env->NewString(reinterpret_cast<const jchar*>(text.data()), static_cast<jsize>(text.size()));
}

// JavaVMOption strings use a platform encoding on Windows. Pass paths as JNI UTF-16
// strings instead, so non-ASCII installation directories do not corrupt the classpath.
static jclass loadWorker(JNIEnv* env, const std::u16string& classpath, char16_t separator) {
    const auto fileClass = env->FindClass("java/io/File");
    const auto uriClass = env->FindClass("java/net/URI");
    const auto urlClass = env->FindClass("java/net/URL");
    const auto loaderClass = env->FindClass("java/net/URLClassLoader");
    if (env->ExceptionCheck()) return nullptr;
    const auto fileConstructor = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");
    const auto toUri = env->GetMethodID(fileClass, "toURI", "()Ljava/net/URI;");
    const auto toUrl = env->GetMethodID(uriClass, "toURL", "()Ljava/net/URL;");
    const auto create = env->GetStaticMethodID(loaderClass, "newInstance", "([Ljava/net/URL;)Ljava/net/URLClassLoader;");
    const auto load = env->GetMethodID(loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (env->ExceptionCheck()) return nullptr;
    std::vector<std::u16string> paths;
    size_t start = 0;
    while (start < classpath.size()) {
        const auto end = classpath.find(separator, start);
        paths.push_back(classpath.substr(start, end == std::u16string::npos ? end : end - start));
        if (end == std::u16string::npos) break;
        start = end + 1;
    }
    const auto urls = env->NewObjectArray(static_cast<jsize>(paths.size()), urlClass, nullptr);
    if (!urls) return nullptr;
    for (size_t i = 0; i < paths.size(); ++i) {
        if (env->PushLocalFrame(8) < 0) return nullptr;
        const auto file = env->NewObject(fileClass, fileConstructor, javaText(env, paths[i]));
        const auto uri = file && !env->ExceptionCheck() ? env->CallObjectMethod(file, toUri) : nullptr;
        const auto url = uri && !env->ExceptionCheck() ? env->CallObjectMethod(uri, toUrl) : nullptr;
        if (url && !env->ExceptionCheck()) env->SetObjectArrayElement(urls, static_cast<jsize>(i), url);
        env->PopLocalFrame(nullptr);
        if (env->ExceptionCheck()) return nullptr;
    }
    const auto loader = env->CallStaticObjectMethod(loaderClass, create, urls);
    if (!loader || env->ExceptionCheck()) return nullptr;
    const auto threadClass = env->FindClass("java/lang/Thread");
    const auto current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    const auto context = env->GetMethodID(threadClass, "setContextClassLoader", "(Ljava/lang/ClassLoader;)V");
    if (env->ExceptionCheck()) return nullptr;
    env->CallVoidMethod(env->CallStaticObjectMethod(threadClass, current), context, loader);
    if (env->ExceptionCheck()) return nullptr;
    return static_cast<jclass>(env->CallObjectMethod(loader, load, javaText(env, u"dev.gaboron.spwmedia.worker.WorkerMainKt")));
}

static int launch(CreateVM create, const std::u16string& classpath, char16_t separator, const std::u16string& media) {
    if (!create) { std::fputs("JVM invocation entry point unavailable\n", stderr); return 3; }
    JavaVMOption options[] = {{const_cast<char*>("--enable-native-access=ALL-UNNAMED"), nullptr}};
    JavaVMInitArgs arguments{};
    arguments.version = JNI_VERSION_1_8;
    arguments.nOptions = 1;
    arguments.options = options;
    arguments.ignoreUnrecognized = JNI_FALSE;
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (create(&vm, reinterpret_cast<void**>(&env), &arguments) != JNI_OK) {
        std::fputs("Cannot initialize the host JVM runtime\n", stderr); return 4;
    }
    auto strings = env->FindClass("java/lang/String");
    auto worker = loadWorker(env, classpath, separator);
    auto main = worker ? env->GetStaticMethodID(worker, "main", "([Ljava/lang/String;)V") : nullptr;
    if (strings && main && !env->ExceptionCheck()) {
        auto values = env->NewObjectArray(1, strings, nullptr);
        auto library = javaText(env, media);
        if (values && library && !env->ExceptionCheck()) {
            env->SetObjectArrayElement(values, 0, library);
            if (!env->ExceptionCheck()) env->CallStaticVoidMethod(worker, main, values);
        }
    }
    const bool failed = env->ExceptionCheck();
    if (failed) { env->ExceptionDescribe(); env->ExceptionClear(); }
    vm->DestroyJavaVM();
    return failed ? 5 : 0;
}

#ifdef _WIN32
static std::u16string utf16(const wchar_t* text) {
    return std::u16string(reinterpret_cast<const char16_t*>(text));
}
int wmain(int count, wchar_t** values) {
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX);
    if (count != 4) return 2;
    const auto bin = std::filesystem::path(values[1]) / L"bin";
    SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS | LOAD_LIBRARY_SEARCH_USER_DIRS);
    AddDllDirectory(bin.c_str());
    const auto module = LoadLibraryExW((bin / L"server/jvm.dll").c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS | LOAD_LIBRARY_SEARCH_USER_DIRS);
    if (!module) { std::fputs("Cannot load the host JVM library\n", stderr); return 3; }
    return launch(reinterpret_cast<CreateVM>(GetProcAddress(module, "JNI_CreateJavaVM")), utf16(values[2]), u';', utf16(values[3]));
}
#else
int main(int count, char** values) {
    if (count != 4) return 2;
    const auto library = std::filesystem::path(values[1]) / "lib/server/libjvm.so";
    const auto module = dlopen(library.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!module) { std::fputs("Cannot load the host JVM library\n", stderr); return 3; }
    std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> unicode;
    return launch(reinterpret_cast<CreateVM>(dlsym(module, "JNI_CreateJavaVM")), unicode.from_bytes(values[2]), u':', unicode.from_bytes(values[3]));
}
#endif
