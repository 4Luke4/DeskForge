#include <aaudio/AAudio.h>
#include <android/log.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <array>
#include <cstring>
#include <cmath>
#include <mutex>
#include <string>
#include <utility>
#include <memory>
#include <optional>
#include <vector>

#include "rfb_client.h"
#include "rfb_clipboard.h"
#include "audio_bridge.h"

namespace {

constexpr char kLogTag[] = "DeskForgeEngine";
std::mutex g_session_mutex;
pid_t g_session_pid = -1;
std::string g_last_error;
std::unique_ptr<RfbClient> g_rfb_client;
std::unique_ptr<AudioBridge> g_audio_bridge;

bool is_regular_executable(const char* path) {
    struct stat file_status {};
    return path != nullptr && stat(path, &file_status) == 0 && S_ISREG(file_status.st_mode) &&
           access(path, X_OK) == 0;
}

bool is_directory(const char* path) {
    struct stat file_status {};
    return path != nullptr && stat(path, &file_status) == 0 && S_ISDIR(file_status.st_mode);
}

void set_error(std::string message) {
    g_last_error = std::move(message);
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", g_last_error.c_str());
}

std::string from_jstring(JNIEnv* environment, jstring value) {
    if (value == nullptr) return {};
    const char* utf_value = environment->GetStringUTFChars(value, nullptr);
    if (utf_value == nullptr) return {};
    std::string result(utf_value);
    environment->ReleaseStringUTFChars(value, utf_value);
    return result;
}

bool has_aaudio() {
    AAudioStreamBuilder* builder = nullptr;
    const aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (builder != nullptr) AAudioStreamBuilder_delete(builder);
    return result == AAUDIO_OK;
}

pid_t active_session_pid() {
    if (g_session_pid <= 0) return -1;
    int status = 0;
    const pid_t result = waitpid(g_session_pid, &status, WNOHANG);
    if (result == g_session_pid || (result < 0 && errno == ECHILD)) {
        g_session_pid = -1;
        if (g_rfb_client != nullptr) {
            g_rfb_client->stop();
            g_rfb_client.reset();
        }
        if (g_audio_bridge != nullptr) {
            g_audio_bridge->stop();
            g_audio_bridge.reset();
        }
    }
    return g_session_pid;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeInspect(
    JNIEnv* environment,
    jobject,
    jstring proot_path_value) {
    const std::string proot_path = from_jstring(environment, proot_path_value);
    const bool proot_available = is_regular_executable(proot_path.c_str());
    const bool audio_available = has_aaudio();
    const std::string detail = proot_available
        ? "Verified runtime executable discovered"
        : "Verified runtime executable is absent";
    const std::string response =
        std::string(proot_available ? "true" : "false") + "|" +
        (audio_available ? "true" : "false") + "|" + detail;
    return environment->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeCreateGraphicsListener(
    JNIEnv* environment,
    jobject,
    jstring path_value) {
    const std::string path = from_jstring(environment, path_value);
    sockaddr_un address{};
    if (path.empty() || path.size() >= sizeof(address.sun_path)) return -1;

    const int descriptor = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (descriptor < 0) return -1;
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, path.c_str(), path.size() + 1);
    unlink(path.c_str());
    if (bind(descriptor, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 ||
        chmod(path.c_str(), S_IRUSR | S_IWUSR) != 0 || listen(descriptor, 32) != 0) {
        close(descriptor);
        unlink(path.c_str());
        return -1;
    }
    return descriptor;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeStart(
    JNIEnv* environment,
    jobject,
    jstring proot_path_value,
    jstring proot_loader_path_value,
    jstring rootfs_path_value,
    jstring runtime_directory_path_value,
    jobject surface,
    jint viewport_width,
    jint viewport_height,
    jint density_dpi,
    jfloat refresh_rate_hz,
    jstring renderer_preference_value) {
    const std::string proot_path = from_jstring(environment, proot_path_value);
    const std::string proot_loader_path = from_jstring(environment, proot_loader_path_value);
    const std::string rootfs_path = from_jstring(environment, rootfs_path_value);
    const std::string runtime_directory_path = from_jstring(environment, runtime_directory_path_value);
    const std::string renderer_preference = from_jstring(environment, renderer_preference_value);
    std::lock_guard<std::mutex> lock(g_session_mutex);

    if (active_session_pid() > 0) {
        set_error("A managed Linux session is already running");
        return -1;
    }
    if (!is_regular_executable(proot_path.c_str())) {
        set_error("The verified PRoot runtime is unavailable");
        return -1;
    }
    if (!is_regular_executable(proot_loader_path.c_str())) {
        set_error("The verified PRoot runtime is unavailable");
        return -1;
    }
    if (!is_directory(rootfs_path.c_str())) {
        set_error("The selected root filesystem is unavailable");
        return -1;
    }
    if (!is_directory(runtime_directory_path.c_str())) {
        set_error("The PRoot temporary directory is unavailable");
        return -1;
    }
    const std::string shared_memory_path = runtime_directory_path + "/dev-shm";
    if (!is_directory(shared_memory_path.c_str())) {
        set_error("The private guest shared-memory directory is unavailable");
        return -1;
    }
    if (surface == nullptr || viewport_width < 640 || viewport_width > 4096 ||
        viewport_height < 480 || viewport_height > 4096 || density_dpi < 120 || density_dpi > 640 ||
        static_cast<int64_t>(viewport_width) * viewport_height > 16'777'216 ||
        !std::isfinite(refresh_rate_hz) || refresh_rate_hz < 30.0F || refresh_rate_hz > 240.0F ||
        (renderer_preference != "auto" && renderer_preference != "venus" &&
         renderer_preference != "virgl" && renderer_preference != "llvmpipe")) {
        set_error("The desktop viewport is invalid");
        return -1;
    }

    auto audio_bridge = std::make_unique<AudioBridge>();
    if (!audio_bridge->prepare(runtime_directory_path)) {
        set_error(audio_bridge->last_error());
        return -1;
    }

    int exec_status_pipe[2] = {-1, -1};
    if (pipe2(exec_status_pipe, O_CLOEXEC) != 0) {
        set_error(std::string("Unable to create the launch handshake: ") + std::strerror(errno));
        return -1;
    }

    const pid_t child = fork();
    if (child < 0) {
        close(exec_status_pipe[0]);
        close(exec_status_pipe[1]);
        set_error(std::string("Unable to create the session process: ") + std::strerror(errno));
        return -1;
    }
    if (child == 0) {
        close(exec_status_pipe[0]);
        // A dedicated process group lets the supervisor stop every guest descendant atomically.
        if (setsid() < 0) {
            const int launch_error = errno;
            const ssize_t written = write(exec_status_pipe[1], &launch_error, sizeof(launch_error));
            (void)written;
            _exit(125);
        }
        setenv("HOME", "/root", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("DISPLAY", ":0", 1);
        setenv("XDG_RUNTIME_DIR", "/run/deskforge", 1);
        const std::string refresh_rate = std::to_string(refresh_rate_hz);
        // Keep executable code in the signed native-lib directory; code cache is scratch only.
        if (setenv("PROOT_LOADER", proot_loader_path.c_str(), 1) != 0 ||
            setenv("PROOT_TMP_DIR", runtime_directory_path.c_str(), 1) != 0 ||
            setenv("DESKFORGE_RENDERER", renderer_preference.c_str(), 1) != 0 ||
            setenv("DESKFORGE_REFRESH_RATE", refresh_rate.c_str(), 1) != 0) {
            const int launch_error = errno;
            const ssize_t written = write(exec_status_pipe[1], &launch_error, sizeof(launch_error));
            (void)written;
            _exit(125);
        }
        const std::string runtime_bind = runtime_directory_path + ":/run/deskforge";
        const std::string shared_memory_bind = shared_memory_path + ":/dev/shm";
        const std::string width = std::to_string(viewport_width);
        const std::string height = std::to_string(viewport_height);
        const std::string dpi = std::to_string(density_dpi);
        execl(
            proot_path.c_str(),
            proot_path.c_str(),
            "--kill-on-exit",
            "-0",
            "-r",
            rootfs_path.c_str(),
            // Do not expose Binder, raw audio, input, or other Android device nodes to guest code.
            "-b",
            "/dev/null",
            "-b",
            "/dev/zero",
            "-b",
            "/dev/random",
            "-b",
            "/dev/urandom",
            "-b",
            "/dev/ptmx",
            "-b",
            "/dev/pts",
            "-b",
            shared_memory_bind.c_str(),
            "-b",
            "/proc",
            "-b",
            runtime_bind.c_str(),
            "/usr/libexec/deskforge/desktop-session",
            width.c_str(),
            height.c_str(),
            dpi.c_str(),
            static_cast<char*>(nullptr));
        const int launch_error = errno;
        const ssize_t written = write(exec_status_pipe[1], &launch_error, sizeof(launch_error));
        (void)written;
        _exit(126);
    }

    close(exec_status_pipe[1]);
    int launch_error = 0;
    ssize_t read_result = 0;
    do {
        read_result = read(exec_status_pipe[0], &launch_error, sizeof(launch_error));
    } while (read_result < 0 && errno == EINTR);
    const int handshake_error = errno;
    close(exec_status_pipe[0]);
    if (read_result != 0) {
        if (read_result < 0) {
            // A broken handshake cannot establish success, so terminate the unconfirmed child.
            kill(child, SIGKILL);
        }
        int status = 0;
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
            // Reap the failed launcher before reporting the handshake error.
        }
        set_error(
            read_result > 0
                ? std::string("Unable to execute the PRoot runtime: ") + std::strerror(launch_error)
                : std::string("Unable to confirm the PRoot launch: ") + std::strerror(handshake_error));
        return -1;
    }

    if (!audio_bridge->start()) {
        const std::string audio_error = audio_bridge->last_error();
        audio_bridge->stop();
        kill(-child, SIGKILL);
        int status = 0;
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
            // Reap the guest when its private host audio transport cannot start.
        }
        set_error(audio_error.empty() ? "Unable to start the private audio bridge" : audio_error);
        return -1;
    }

    const std::string rfb_socket = runtime_directory_path + "/rfb.sock";
    auto rfb_client = std::make_unique<RfbClient>();
    if (!rfb_client->connect_and_start(
            environment, surface, rfb_socket, viewport_width, viewport_height)) {
        const std::string display_error = rfb_client->last_error();
        rfb_client->stop();
        audio_bridge->stop();
        kill(-child, SIGKILL);
        int status = 0;
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
            // Reap every failed desktop launch before returning control to Kotlin.
        }
        set_error(display_error.empty() ? "Unable to connect to the Fedora desktop" : display_error);
        return -1;
    }

    g_rfb_client = std::move(rfb_client);
    g_audio_bridge = std::move(audio_bridge);
    g_session_pid = child;
    g_last_error.clear();
    return static_cast<jint>(child);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    if (active_session_pid() <= 0) {
        set_error("No managed Linux session is running");
        return JNI_FALSE;
    }

    const pid_t process_id = g_session_pid;
    if (g_audio_bridge != nullptr) {
        // Stop capture before guest teardown so no microphone sample outlives consent.
        g_audio_bridge->stop();
        g_audio_bridge.reset();
    }
    if (g_rfb_client != nullptr) {
        g_rfb_client->stop();
        g_rfb_client.reset();
    }
    if (kill(-process_id, SIGTERM) != 0 && errno != ESRCH) {
        set_error(std::string("Unable to stop the session: ") + std::strerror(errno));
        return JNI_FALSE;
    }
    int status = 0;
    bool reaped = false;
    for (int attempt = 0; attempt < 100; ++attempt) {
        const pid_t wait_result = waitpid(process_id, &status, WNOHANG);
        if (wait_result == process_id || (wait_result < 0 && errno == ECHILD)) {
            reaped = true;
            break;
        }
        if (wait_result < 0 && errno != EINTR) break;
        usleep(50'000);
    }
    if (!reaped) {
        // A bounded graceful period prevents a wedged guest from blocking Android lifecycle work.
        if (kill(-process_id, SIGKILL) != 0 && errno != ESRCH) {
            set_error(std::string("Unable to terminate the session: ") + std::strerror(errno));
            return JNI_FALSE;
        }
        while (waitpid(process_id, &status, 0) < 0 && errno == EINTR) {
            // Continue until the force-terminated process has been reaped.
        }
    }
    g_session_pid = -1;
    g_last_error.clear();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeAttachSurface(
    JNIEnv* environment,
    jobject,
    jobject surface,
    jint width,
    jint height) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_rfb_client != nullptr && g_rfb_client->attach_surface(environment, surface, width, height)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeDetachSurface(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    if (g_rfb_client != nullptr) g_rfb_client->detach_surface();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeResizeDisplay(
    JNIEnv*, jobject, jint width, jint height) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_rfb_client != nullptr && g_rfb_client->resize(width, height) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeSendPointer(
    JNIEnv*, jobject, jint x, jint y, jint buttons) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_rfb_client != nullptr && g_rfb_client->send_pointer(x, y, buttons) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeSendKey(
    JNIEnv*, jobject, jint keysym, jboolean pressed) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_rfb_client != nullptr &&
        g_rfb_client->send_key(static_cast<uint32_t>(keysym), pressed == JNI_TRUE)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeSendText(
    JNIEnv* environment, jobject, jintArray keysyms_value) {
    if (keysyms_value == nullptr) return JNI_FALSE;
    const jsize count = environment->GetArrayLength(keysyms_value);
    if (count < 0 || count > 4096) return JNI_FALSE;
    std::vector<jint> java_keysyms(static_cast<size_t>(count));
    if (count > 0) {
        environment->GetIntArrayRegion(keysyms_value, 0, count, java_keysyms.data());
        if (environment->ExceptionCheck() == JNI_TRUE) return JNI_FALSE;
    }
    std::vector<uint32_t> keysyms;
    keysyms.reserve(static_cast<size_t>(count));
    for (const jint keysym : java_keysyms) keysyms.push_back(static_cast<uint32_t>(keysym));

    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_rfb_client != nullptr && g_rfb_client->send_text(keysyms) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeClipboardSnapshot(
    JNIEnv* environment, jobject) {
    RfbClipboardSnapshot snapshot{
        RfbClipboardStatus::Unsupported,
        false,
        RfbClipboardFailure::None,
    };
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        if (g_rfb_client != nullptr) snapshot = g_rfb_client->clipboard_snapshot();
    }
    const std::array<jint, 3> values{
        static_cast<jint>(snapshot.status),
        snapshot.remote_text_available ? 1 : 0,
        static_cast<jint>(snapshot.failure),
    };
    jintArray result = environment->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        environment->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeOfferClipboardText(
    JNIEnv* environment, jobject, jbyteArray text_value) {
    if (text_value == nullptr) return JNI_FALSE;
    const jsize size = environment->GetArrayLength(text_value);
    if (size < 0 || static_cast<size_t>(size) > rfb_clipboard::kMaximumTextBytes) return JNI_FALSE;
    std::vector<uint8_t> text(static_cast<size_t>(size));
    if (size > 0) {
        environment->GetByteArrayRegion(
            text_value, 0, size, reinterpret_cast<jbyte*>(text.data()));
        if (environment->ExceptionCheck() == JNI_TRUE) return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_rfb_client != nullptr && g_rfb_client->offer_clipboard_text(text) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeRequestClipboardText(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_rfb_client != nullptr && g_rfb_client->request_clipboard_text() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeTakeClipboardText(
    JNIEnv* environment, jobject) {
    std::optional<std::vector<uint8_t>> text;
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        if (g_rfb_client != nullptr) text = g_rfb_client->take_clipboard_text();
    }
    if (!text.has_value()) return nullptr;
    jbyteArray result = environment->NewByteArray(static_cast<jsize>(text->size()));
    if (result != nullptr && !text->empty()) {
        environment->SetByteArrayRegion(
            result, 0, static_cast<jsize>(text->size()),
            reinterpret_cast<const jbyte*>(text->data()));
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeDisplayConnected(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_rfb_client != nullptr && g_rfb_client->connected() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeAudioSnapshot(
    JNIEnv* environment, jobject) {
    AudioBridgeSnapshot snapshot{
        AudioPlaybackStatus::Unavailable,
        AudioMicrophoneStatus::Off,
        AudioBridgeFailure::None,
        AAUDIO_UNSPECIFIED,
        AAUDIO_UNSPECIFIED,
        0,
        0,
    };
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        if (g_audio_bridge != nullptr) snapshot = g_audio_bridge->snapshot();
    }
    const std::array<jlong, 7> values{
        static_cast<jlong>(snapshot.playback_status),
        static_cast<jlong>(snapshot.microphone_status),
        static_cast<jlong>(snapshot.failure),
        static_cast<jlong>(snapshot.output_device_id),
        static_cast<jlong>(snapshot.input_device_id),
        static_cast<jlong>(snapshot.underrun_count),
        static_cast<jlong>(snapshot.overflow_count),
    };
    jlongArray result = environment->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        environment->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeSetPlaybackAudible(
    JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_audio_bridge != nullptr &&
        g_audio_bridge->set_playback_audible(enabled == JNI_TRUE)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeSetMicrophoneEnabled(
    JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return g_audio_bridge != nullptr &&
        g_audio_bridge->set_microphone_enabled(enabled == JNI_TRUE)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeActiveProcessId(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return static_cast<jint>(active_session_pid());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeLastError(JNIEnv* environment, jobject) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    return environment->NewStringUTF(g_last_error.c_str());
}
