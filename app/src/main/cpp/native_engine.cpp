#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <mutex>
#include <string>
#include <utility>

namespace {

constexpr char kLogTag[] = "DeskForgeEngine";
std::mutex g_session_mutex;
pid_t g_session_pid = -1;
std::string g_last_error;

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

bool has_vulkan_loader() {
    void* library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return false;
    dlclose(library);
    return true;
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
    const bool vulkan_available = has_vulkan_loader();
    const bool audio_available = has_aaudio();
    const std::string detail = proot_available
        ? "Verified runtime executable discovered"
        : "Verified runtime executable is absent";
    const std::string response =
        std::string(proot_available ? "true" : "false") + "|" +
        (vulkan_available ? "true" : "false") + "|" +
        (audio_available ? "true" : "false") + "|" + detail;
    return environment->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_deskforge_app_engine_NativeDeskForgeEngine_nativeStart(
    JNIEnv* environment,
    jobject,
    jstring proot_path_value,
    jstring proot_loader_path_value,
    jstring rootfs_path_value,
    jstring runtime_directory_path_value,
    jboolean microphone_enabled) {
    const std::string proot_path = from_jstring(environment, proot_path_value);
    const std::string proot_loader_path = from_jstring(environment, proot_loader_path_value);
    const std::string rootfs_path = from_jstring(environment, rootfs_path_value);
    const std::string runtime_directory_path = from_jstring(environment, runtime_directory_path_value);
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
        setenv("PULSE_SERVER", "unix:/run/deskforge/pulse.sock", 1);
        // Keep executable code in the signed native-lib directory; code cache is scratch only.
        if (setenv("PROOT_LOADER", proot_loader_path.c_str(), 1) != 0 ||
            setenv("PROOT_TMP_DIR", runtime_directory_path.c_str(), 1) != 0) {
            const int launch_error = errno;
            const ssize_t written = write(exec_status_pipe[1], &launch_error, sizeof(launch_error));
            (void)written;
            _exit(125);
        }
        setenv("DESKFORGE_MICROPHONE", microphone_enabled == JNI_TRUE ? "enabled" : "disabled", 1);
        execl(
            proot_path.c_str(),
            proot_path.c_str(),
            "--kill-on-exit",
            "-0",
            "-r",
            rootfs_path.c_str(),
            "-b",
            "/dev",
            "-b",
            "/proc",
            "/usr/bin/startxfce4",
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
