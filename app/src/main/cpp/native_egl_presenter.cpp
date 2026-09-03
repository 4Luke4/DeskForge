#include "native_egl_presenter.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <poll.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <limits>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

constexpr int kPixelBytes = 4;
constexpr size_t kMaximumTimingSamples = 600;
constexpr auto kStartupTimeout = std::chrono::seconds(3);

bool has_extension(const char* extensions, const char* requested) {
    if (extensions == nullptr || requested == nullptr || std::strchr(requested, ' ') != nullptr) {
        return false;
    }
    const size_t requested_length = std::strlen(requested);
    const char* position = extensions;
    while ((position = std::strstr(position, requested)) != nullptr) {
        const bool starts_word = position == extensions || position[-1] == ' ';
        const bool ends_word = position[requested_length] == '\0' || position[requested_length] == ' ';
        if (starts_word && ends_word) return true;
        position += requested_length;
    }
    return false;
}

bool wait_for_fence(int descriptor) {
    if (descriptor < 0) return true;
    pollfd item{descriptor, POLLIN, 0};
    int result;
    do {
        result = poll(&item, 1, 3000);
    } while (result < 0 && errno == EINTR);
    close(descriptor);
    return result > 0 && (item.revents & POLLIN) != 0;
}

GLuint compile_shader(GLenum type, const char* source) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE) return shader;
    glDeleteShader(shader);
    return 0;
}

}  // namespace

class NativeEglPresenter::Impl {
public:
    ~Impl() { stop(); }

    bool start(
        JNIEnv* environment,
        jobject surface,
        int viewport_width,
        int viewport_height,
        float target_refresh_rate_hz,
        float active_refresh_rate_hz) {
        if (!valid_display_mode(
                viewport_width,
                viewport_height,
                target_refresh_rate_hz,
                active_refresh_rate_hz)) {
            return fail("Invalid presentation display mode");
        }
        ANativeWindow* window = surface == nullptr
            ? nullptr
            : ANativeWindow_fromSurface(environment, surface);
        if (window == nullptr) return fail("Unable to acquire the Android presentation surface");

        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (thread_.joinable()) {
                ANativeWindow_release(window);
                return false;
            }
            requested_window_ = window;
            viewport_width_ = viewport_width;
            viewport_height_ = viewport_height;
            snapshot_.status = NativePresentationStatus::Starting;
            snapshot_.path = NativePresentationPath::NativeEglUpload;
            snapshot_.target_refresh_rate_hz = target_refresh_rate_hz;
            snapshot_.active_refresh_rate_hz = active_refresh_rate_hz;
            detail_ = "Native EGL initialization is pending";
            stopping_ = false;
            ++surface_generation_;
        }
        thread_ = std::thread(&Impl::run, this);
        std::unique_lock<std::mutex> lock(mutex_);
        const bool completed = condition_.wait_for(lock, kStartupTimeout, [this] {
            return snapshot_.status == NativePresentationStatus::Ready ||
                snapshot_.status == NativePresentationStatus::Failed;
        });
        if (!completed || snapshot_.status != NativePresentationStatus::Ready) {
            lock.unlock();
            stop();
            return false;
        }
        return true;
    }

    bool attach_surface(
        JNIEnv* environment,
        jobject surface,
        int viewport_width,
        int viewport_height,
        float target_refresh_rate_hz,
        float active_refresh_rate_hz) {
        if (!valid_display_mode(
                viewport_width,
                viewport_height,
                target_refresh_rate_hz,
                active_refresh_rate_hz) || surface == nullptr) {
            return false;
        }
        ANativeWindow* window = ANativeWindow_fromSurface(environment, surface);
        if (window == nullptr) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (!thread_.joinable() || stopping_) {
            ANativeWindow_release(window);
            return false;
        }
        if (requested_window_ != nullptr) ANativeWindow_release(requested_window_);
        requested_window_ = window;
        viewport_width_ = viewport_width;
        viewport_height_ = viewport_height;
        snapshot_.target_refresh_rate_hz = target_refresh_rate_hz;
        snapshot_.active_refresh_rate_hz = active_refresh_rate_hz;
        if (!staging_framebuffer_.empty()) {
            merge_damage(0, 0, framebuffer_width_, framebuffer_height_);
            ++frame_generation_;
        }
        snapshot_.status = NativePresentationStatus::Starting;
        ++surface_generation_;
        condition_.notify_all();
        return true;
    }

    void detach_surface() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (requested_window_ != nullptr) {
            ANativeWindow_release(requested_window_);
            requested_window_ = nullptr;
        }
        if (!stopping_ && snapshot_.status != NativePresentationStatus::Failed) {
            snapshot_.status = NativePresentationStatus::SurfaceDetached;
            detail_ = "Android surface detached; decoded desktop state is retained";
        }
        ++surface_generation_;
        condition_.notify_all();
    }

    void update_display_mode(
        int viewport_width,
        int viewport_height,
        float target_refresh_rate_hz,
        float active_refresh_rate_hz) {
        if (!valid_display_mode(
                viewport_width,
                viewport_height,
                target_refresh_rate_hz,
                active_refresh_rate_hz)) {
            return;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        viewport_width_ = viewport_width;
        viewport_height_ = viewport_height;
        snapshot_.target_refresh_rate_hz = target_refresh_rate_hz;
        snapshot_.active_refresh_rate_hz = active_refresh_rate_hz;
        if (!staging_framebuffer_.empty()) {
            merge_damage(0, 0, framebuffer_width_, framebuffer_height_);
        }
        ++frame_generation_;
        condition_.notify_all();
    }

    void submit(
        const std::vector<uint8_t>& framebuffer,
        int framebuffer_width,
        int framebuffer_height,
        int damage_x,
        int damage_y,
        int damage_width,
        int damage_height) {
        if (framebuffer_width <= 0 || framebuffer_height <= 0 || damage_width <= 0 ||
            damage_height <= 0 || damage_x < 0 || damage_y < 0 ||
            damage_x > framebuffer_width - damage_width ||
            damage_y > framebuffer_height - damage_height) {
            return;
        }
        const size_t expected_size = static_cast<size_t>(framebuffer_width) *
            static_cast<size_t>(framebuffer_height) * kPixelBytes;
        if (framebuffer.size() != expected_size) return;

        std::lock_guard<std::mutex> lock(mutex_);
        if (stopping_) return;
        if (framebuffer_width_ != framebuffer_width || framebuffer_height_ != framebuffer_height) {
            framebuffer_width_ = framebuffer_width;
            framebuffer_height_ = framebuffer_height;
            staging_framebuffer_.assign(expected_size, 0);
            damage_x = 0;
            damage_y = 0;
            damage_width = framebuffer_width;
            damage_height = framebuffer_height;
            texture_reset_required_ = true;
            damage_pending_ = false;
        }
        for (int row = damage_y; row < damage_y + damage_height; ++row) {
            const size_t offset =
                (static_cast<size_t>(row) * framebuffer_width + damage_x) * kPixelBytes;
            std::memcpy(
                staging_framebuffer_.data() + offset,
                framebuffer.data() + offset,
                static_cast<size_t>(damage_width) * kPixelBytes);
        }
        merge_damage(damage_x, damage_y, damage_width, damage_height);
        ++frame_generation_;
        condition_.notify_all();
    }

    void stop() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
            condition_.notify_all();
        }
        if (thread_.joinable() && thread_.get_id() != std::this_thread::get_id()) thread_.join();
        std::lock_guard<std::mutex> lock(mutex_);
        if (requested_window_ != nullptr) {
            ANativeWindow_release(requested_window_);
            requested_window_ = nullptr;
        }
        if (snapshot_.status != NativePresentationStatus::Failed) {
            snapshot_.status = NativePresentationStatus::Stopped;
            detail_ = "Native EGL presenter stopped";
        }
    }

    [[nodiscard]] bool ready() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return snapshot_.status == NativePresentationStatus::Ready ||
            snapshot_.status == NativePresentationStatus::SurfaceDetached;
    }

    [[nodiscard]] NativePresentationSnapshot snapshot() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return snapshot_;
    }

    [[nodiscard]] std::string detail() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return detail_;
    }

private:
    static bool valid_display_mode(int width, int height, float target, float active) {
        return width > 0 && height > 0 && std::isfinite(target) && std::isfinite(active) &&
            target >= 30.0F && target <= 240.0F && active >= 30.0F && active <= 240.0F;
    }

    bool fail(std::string detail) {
        std::lock_guard<std::mutex> lock(mutex_);
        snapshot_.status = NativePresentationStatus::Failed;
        detail_ = std::move(detail);
        condition_.notify_all();
        return false;
    }

    void fail_from_thread(std::string detail) {
        std::lock_guard<std::mutex> lock(mutex_);
        snapshot_.status = NativePresentationStatus::Failed;
        detail_ = std::move(detail);
        condition_.notify_all();
    }

    void merge_damage(int x, int y, int width, int height) {
        if (!damage_pending_) {
            damage_x_ = x;
            damage_y_ = y;
            damage_right_ = x + width;
            damage_bottom_ = y + height;
            damage_pending_ = true;
            return;
        }
        damage_x_ = std::min(damage_x_, x);
        damage_y_ = std::min(damage_y_, y);
        damage_right_ = std::max(damage_right_, x + width);
        damage_bottom_ = std::max(damage_bottom_, y + height);
    }

    void run() {
        if (!initialize_egl()) {
            cleanup_egl();
            return;
        }
        uint64_t observed_surface_generation = std::numeric_limits<uint64_t>::max();
        uint64_t observed_frame_generation = std::numeric_limits<uint64_t>::max();
        while (true) {
            ANativeWindow* next_window = nullptr;
            bool surface_changed = false;
            bool frame_changed = false;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                condition_.wait(lock, [this, observed_surface_generation, observed_frame_generation] {
                    return stopping_ || surface_generation_ != observed_surface_generation ||
                        frame_generation_ != observed_frame_generation;
                });
                if (stopping_) break;
                surface_changed = surface_generation_ != observed_surface_generation;
                frame_changed = frame_generation_ != observed_frame_generation;
                observed_surface_generation = surface_generation_;
                observed_frame_generation = frame_generation_;
                if (surface_changed && requested_window_ != nullptr) {
                    ANativeWindow_acquire(requested_window_);
                    next_window = requested_window_;
                }
            }

            if (surface_changed) {
                if (!replace_window_surface(next_window)) {
                    if (next_window != nullptr) ANativeWindow_release(next_window);
                    fail_from_thread("Unable to create the EGL window surface");
                    break;
                }
                if (next_window != nullptr) {
                    std::lock_guard<std::mutex> lock(mutex_);
                    snapshot_.status = NativePresentationStatus::Ready;
                }
            }
            if (next_window != nullptr) ANativeWindow_release(next_window);
            if ((surface_changed || frame_changed) && egl_surface_ != EGL_NO_SURFACE && !present_frame()) {
                fail_from_thread("Native EGL presentation failed");
                break;
            }
        }
        cleanup_egl();
    }

    bool initialize_egl() {
        egl_display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (egl_display_ == EGL_NO_DISPLAY || eglInitialize(egl_display_, nullptr, nullptr) != EGL_TRUE) {
            fail_from_thread("EGL display initialization failed");
            return false;
        }
        const EGLint attributes[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 0,
            EGL_NONE,
        };
        EGLint count = 0;
        if (eglChooseConfig(egl_display_, attributes, &egl_config_, 1, &count) != EGL_TRUE || count != 1) {
            fail_from_thread("A compatible EGL window configuration is unavailable");
            return false;
        }
        const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        egl_context_ = eglCreateContext(
            egl_display_, egl_config_, EGL_NO_CONTEXT, context_attributes);
        if (egl_context_ == EGL_NO_CONTEXT) {
            fail_from_thread("The EGL ES 2 context could not be created");
            return false;
        }
        ANativeWindow* initial_window = current_requested_window();
        const bool surface_ready = replace_window_surface(initial_window);
        if (initial_window != nullptr) ANativeWindow_release(initial_window);
        if (!surface_ready) {
            fail_from_thread("The initial EGL window surface could not be created");
            return false;
        }
        if (!initialize_gl()) {
            fail_from_thread("The presentation shader could not be initialized");
            return false;
        }
        const char* egl_extensions = eglQueryString(egl_display_, EGL_EXTENSIONS);
        const char* gl_extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
        direct_buffer_supported_ = has_extension(egl_extensions, "EGL_ANDROID_image_native_buffer") &&
            has_extension(gl_extensions, "GL_OES_EGL_image");
        get_native_client_buffer_ = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        create_image_ = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
            eglGetProcAddress("eglCreateImageKHR"));
        destroy_image_ = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
            eglGetProcAddress("eglDestroyImageKHR"));
        image_target_texture_ = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        direct_buffer_supported_ = direct_buffer_supported_ && get_native_client_buffer_ != nullptr &&
            create_image_ != nullptr && destroy_image_ != nullptr && image_target_texture_ != nullptr;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            snapshot_.status = NativePresentationStatus::Ready;
            detail_ = direct_buffer_supported_
                ? "Native EGL ready; hardware-buffer qualification is pending the first frame"
                : "Native EGL upload path selected because hardware-buffer import is unavailable";
            condition_.notify_all();
        }
        return true;
    }

    ANativeWindow* current_requested_window() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (requested_window_ != nullptr) ANativeWindow_acquire(requested_window_);
        return requested_window_;
    }

    bool replace_window_surface(ANativeWindow* window) {
        if (egl_surface_ != EGL_NO_SURFACE) {
            eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(egl_display_, egl_surface_);
            egl_surface_ = EGL_NO_SURFACE;
        }
        if (window == nullptr) return true;
        ANativeWindow_setBuffersGeometry(window, 0, 0, WINDOW_FORMAT_RGBX_8888);
        egl_surface_ = eglCreateWindowSurface(egl_display_, egl_config_, window, nullptr);
        if (egl_surface_ == EGL_NO_SURFACE ||
            eglMakeCurrent(egl_display_, egl_surface_, egl_surface_, egl_context_) != EGL_TRUE) {
            return false;
        }
        eglSwapInterval(egl_display_, 1);
        return true;
    }

    bool initialize_gl() {
        constexpr char vertex_source[] =
            "attribute vec4 position; attribute vec2 textureCoordinate;"
            "varying vec2 coordinate; void main(){gl_Position=position;coordinate=textureCoordinate;}";
        constexpr char fragment_source[] =
            "precision mediump float; varying vec2 coordinate; uniform sampler2D frame;"
            "void main(){gl_FragColor=texture2D(frame,coordinate);}";
        const GLuint vertex = compile_shader(GL_VERTEX_SHADER, vertex_source);
        const GLuint fragment = compile_shader(GL_FRAGMENT_SHADER, fragment_source);
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
            return false;
        }
        program_ = glCreateProgram();
        glAttachShader(program_, vertex);
        glAttachShader(program_, fragment);
        glBindAttribLocation(program_, 0, "position");
        glBindAttribLocation(program_, 1, "textureCoordinate");
        glLinkProgram(program_);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        GLint linked = GL_FALSE;
        glGetProgramiv(program_, GL_LINK_STATUS, &linked);
        if (linked != GL_TRUE) return false;
        glGenTextures(1, &texture_);
        glBindTexture(GL_TEXTURE_2D, texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glUseProgram(program_);
        glUniform1i(glGetUniformLocation(program_, "frame"), 0);
        return glGetError() == GL_NO_ERROR;
    }

    bool present_frame() {
        int framebuffer_width;
        int framebuffer_height;
        int viewport_width;
        int viewport_height;
        int damage_x;
        int damage_y;
        int damage_width;
        int damage_height;
        bool reset_texture;
        std::vector<uint8_t> damaged_pixels;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!damage_pending_ || staging_framebuffer_.empty()) return true;
            framebuffer_width = framebuffer_width_;
            framebuffer_height = framebuffer_height_;
            viewport_width = viewport_width_;
            viewport_height = viewport_height_;
            damage_x = damage_x_;
            damage_y = damage_y_;
            damage_width = damage_right_ - damage_x_;
            damage_height = damage_bottom_ - damage_y_;
            reset_texture = texture_reset_required_;
            if (!direct_buffer_supported_) {
                damaged_pixels.resize(
                    static_cast<size_t>(damage_width) * damage_height * kPixelBytes);
                for (int row = 0; row < damage_height; ++row) {
                    const size_t source_offset =
                        (static_cast<size_t>(damage_y + row) * framebuffer_width + damage_x) *
                        kPixelBytes;
                    const size_t destination_offset =
                        static_cast<size_t>(row) * damage_width * kPixelBytes;
                    std::memcpy(
                        damaged_pixels.data() + destination_offset,
                        staging_framebuffer_.data() + source_offset,
                        static_cast<size_t>(damage_width) * kPixelBytes);
                }
            }
            damage_pending_ = false;
            texture_reset_required_ = false;
        }

        if (reset_texture) {
            release_hardware_buffer();
            if (direct_buffer_supported_ && !create_hardware_buffer(framebuffer_width, framebuffer_height)) {
                direct_buffer_supported_ = false;
                damaged_pixels.resize(
                    static_cast<size_t>(damage_width) * damage_height * kPixelBytes);
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    for (int row = 0; row < damage_height; ++row) {
                        const size_t source_offset =
                            (static_cast<size_t>(damage_y + row) * framebuffer_width + damage_x) *
                            kPixelBytes;
                        const size_t destination_offset =
                            static_cast<size_t>(row) * damage_width * kPixelBytes;
                        std::memcpy(
                            damaged_pixels.data() + destination_offset,
                            staging_framebuffer_.data() + source_offset,
                            static_cast<size_t>(damage_width) * kPixelBytes);
                    }
                }
                std::lock_guard<std::mutex> lock(mutex_);
                snapshot_.path = NativePresentationPath::NativeEglUpload;
                detail_ = "Native EGL upload selected after hardware-buffer qualification failed";
            }
            if (!direct_buffer_supported_) {
                glBindTexture(GL_TEXTURE_2D, texture_);
                glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA,
                    framebuffer_width,
                    framebuffer_height,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    nullptr);
            }
        }

        if (direct_buffer_supported_) {
            ARect rect{damage_x, damage_y, damage_x + damage_width, damage_y + damage_height};
            void* address = nullptr;
            const int lock_result = AHardwareBuffer_lock(
                hardware_buffer_, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, &rect, &address);
            bool hardware_buffer_ready = lock_result == 0 && address != nullptr;
            if (hardware_buffer_ready) {
                AHardwareBuffer_Desc description{};
                AHardwareBuffer_describe(hardware_buffer_, &description);
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    for (int row = 0; row < damage_height; ++row) {
                        const size_t source_offset =
                            (static_cast<size_t>(damage_y + row) * framebuffer_width + damage_x) *
                            kPixelBytes;
                        const size_t destination_offset =
                            (static_cast<size_t>(damage_y + row) * description.stride + damage_x) *
                            kPixelBytes;
                        std::memcpy(
                            static_cast<uint8_t*>(address) + destination_offset,
                            staging_framebuffer_.data() + source_offset,
                            static_cast<size_t>(damage_width) * kPixelBytes);
                    }
                }
            }
            int fence = -1;
            if (lock_result == 0) {
                const int unlock_result = AHardwareBuffer_unlock(hardware_buffer_, &fence);
                const bool fence_ready = wait_for_fence(fence);
                hardware_buffer_ready = hardware_buffer_ready && unlock_result == 0 && fence_ready;
            }
            if (!hardware_buffer_ready) {
                release_hardware_buffer();
                direct_buffer_supported_ = false;
                std::vector<uint8_t> complete_frame;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    complete_frame = staging_framebuffer_;
                    snapshot_.path = NativePresentationPath::NativeEglUpload;
                    detail_ = "Native EGL upload selected after hardware-buffer synchronization failed";
                }
                glBindTexture(GL_TEXTURE_2D, texture_);
                glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA,
                    framebuffer_width,
                    framebuffer_height,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    complete_frame.data());
            }
        } else {
            glBindTexture(GL_TEXTURE_2D, texture_);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glTexSubImage2D(
                GL_TEXTURE_2D,
                0,
                damage_x,
                damage_y,
                damage_width,
                damage_height,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                damaged_pixels.data());
        }

        EGLint surface_width = viewport_width;
        EGLint surface_height = viewport_height;
        eglQuerySurface(egl_display_, egl_surface_, EGL_WIDTH, &surface_width);
        eglQuerySurface(egl_display_, egl_surface_, EGL_HEIGHT, &surface_height);
        if (surface_width <= 0 || surface_height <= 0) return false;
        const double scale = std::min(
            static_cast<double>(surface_width) / framebuffer_width,
            static_cast<double>(surface_height) / framebuffer_height);
        const GLfloat width_scale = static_cast<GLfloat>(framebuffer_width * scale / surface_width);
        const GLfloat height_scale = static_cast<GLfloat>(framebuffer_height * scale / surface_height);
        const std::array<GLfloat, 16> vertices{
            -width_scale, height_scale, 0.0F, 0.0F,
            -width_scale, -height_scale, 0.0F, 1.0F,
            width_scale, height_scale, 1.0F, 0.0F,
            width_scale, -height_scale, 1.0F, 1.0F,
        };
        glViewport(0, 0, surface_width, surface_height);
        glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        glClear(GL_COLOR_BUFFER_BIT);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture_);
        glUseProgram(program_);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), vertices.data());
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), vertices.data() + 2);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glGetError() != GL_NO_ERROR || eglSwapBuffers(egl_display_, egl_surface_) != EGL_TRUE) {
            return false;
        }
        // A single hardware buffer is reused only after sampling completes; this is the explicit
        // producer/consumer fence until a multi-buffer native-fence pipeline is introduced.
        if (direct_buffer_supported_) glFinish();
        const auto completed = std::chrono::steady_clock::now();
        record_frame(completed);
        return true;
    }

    bool create_hardware_buffer(int width, int height) {
        AHardwareBuffer_Desc description{};
        description.width = static_cast<uint32_t>(width);
        description.height = static_cast<uint32_t>(height);
        description.layers = 1;
        description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        description.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        if (AHardwareBuffer_isSupported(&description) != 1 ||
            AHardwareBuffer_allocate(&description, &hardware_buffer_) != 0 ||
            hardware_buffer_ == nullptr) {
            return false;
        }
        EGLClientBuffer client_buffer = get_native_client_buffer_(hardware_buffer_);
        const EGLint image_attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        hardware_image_ = create_image_(
            egl_display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client_buffer, image_attributes);
        if (hardware_image_ == EGL_NO_IMAGE_KHR) {
            release_hardware_buffer();
            return false;
        }
        glBindTexture(GL_TEXTURE_2D, texture_);
        image_target_texture_(GL_TEXTURE_2D, hardware_image_);
        if (glGetError() != GL_NO_ERROR) {
            release_hardware_buffer();
            return false;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        snapshot_.path = NativePresentationPath::NativeHardwareBuffer;
        detail_ = "CPU-writable Android hardware buffer imported directly as an EGL image";
        return true;
    }

    void release_hardware_buffer() {
        if (hardware_image_ != EGL_NO_IMAGE_KHR && destroy_image_ != nullptr) {
            destroy_image_(egl_display_, hardware_image_);
            hardware_image_ = EGL_NO_IMAGE_KHR;
        }
        if (hardware_buffer_ != nullptr) {
            AHardwareBuffer_release(hardware_buffer_);
            hardware_buffer_ = nullptr;
        }
    }

    void record_frame(std::chrono::steady_clock::time_point completed) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (last_frame_completed_.time_since_epoch().count() != 0) {
            const double frame_time_ms =
                std::chrono::duration<double, std::milli>(completed - last_frame_completed_).count();
            timing_samples_ms_.push_back(frame_time_ms);
            if (timing_samples_ms_.size() > kMaximumTimingSamples) timing_samples_ms_.pop_front();
            snapshot_.maximum_frame_time_ms =
                std::max(snapshot_.maximum_frame_time_ms, frame_time_ms);
            const double budget_ms = 1000.0 / std::max(1.0, snapshot_.active_refresh_rate_hz);
            if (frame_time_ms > budget_ms * 1.5) ++snapshot_.missed_frame_budget_count;
            const double elapsed_seconds =
                std::chrono::duration<double>(completed - statistics_window_started_).count();
            if (elapsed_seconds >= 1.0) {
                snapshot_.submitted_frames_per_second =
                    static_cast<double>(statistics_window_frames_) / elapsed_seconds;
                if (!timing_samples_ms_.empty()) {
                    std::vector<double> sorted(timing_samples_ms_.begin(), timing_samples_ms_.end());
                    const size_t index = static_cast<size_t>(
                        std::ceil(static_cast<double>(sorted.size()) * 0.95)) - 1;
                    std::nth_element(sorted.begin(), sorted.begin() + index, sorted.end());
                    snapshot_.p95_frame_time_ms = sorted[index];
                }
                statistics_window_started_ = completed;
                statistics_window_frames_ = 0;
            }
        } else {
            statistics_window_started_ = completed;
        }
        ++statistics_window_frames_;
        last_frame_completed_ = completed;
    }

    void cleanup_egl() {
        release_hardware_buffer();
        if (texture_ != 0) {
            glDeleteTextures(1, &texture_);
            texture_ = 0;
        }
        if (program_ != 0) {
            glDeleteProgram(program_);
            program_ = 0;
        }
        if (egl_display_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (egl_surface_ != EGL_NO_SURFACE) eglDestroySurface(egl_display_, egl_surface_);
            if (egl_context_ != EGL_NO_CONTEXT) eglDestroyContext(egl_display_, egl_context_);
            eglTerminate(egl_display_);
        }
        egl_surface_ = EGL_NO_SURFACE;
        egl_context_ = EGL_NO_CONTEXT;
        egl_display_ = EGL_NO_DISPLAY;
    }

    mutable std::mutex mutex_;
    std::condition_variable condition_;
    std::thread thread_;
    bool stopping_ = false;
    ANativeWindow* requested_window_ = nullptr;
    uint64_t surface_generation_ = 0;
    uint64_t frame_generation_ = 0;
    int viewport_width_ = 0;
    int viewport_height_ = 0;
    int framebuffer_width_ = 0;
    int framebuffer_height_ = 0;
    std::vector<uint8_t> staging_framebuffer_;
    bool damage_pending_ = false;
    int damage_x_ = 0;
    int damage_y_ = 0;
    int damage_right_ = 0;
    int damage_bottom_ = 0;
    bool texture_reset_required_ = true;
    NativePresentationSnapshot snapshot_;
    std::string detail_ = "Native EGL presenter has not been started";
    std::deque<double> timing_samples_ms_;
    std::chrono::steady_clock::time_point last_frame_completed_{};
    std::chrono::steady_clock::time_point statistics_window_started_{};
    uint64_t statistics_window_frames_ = 0;

    EGLDisplay egl_display_ = EGL_NO_DISPLAY;
    EGLConfig egl_config_ = nullptr;
    EGLContext egl_context_ = EGL_NO_CONTEXT;
    EGLSurface egl_surface_ = EGL_NO_SURFACE;
    GLuint program_ = 0;
    GLuint texture_ = 0;
    bool direct_buffer_supported_ = false;
    AHardwareBuffer* hardware_buffer_ = nullptr;
    EGLImageKHR hardware_image_ = EGL_NO_IMAGE_KHR;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC get_native_client_buffer_ = nullptr;
    PFNEGLCREATEIMAGEKHRPROC create_image_ = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroy_image_ = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC image_target_texture_ = nullptr;
};

NativeEglPresenter::NativeEglPresenter() : implementation_(std::make_unique<Impl>()) {}
NativeEglPresenter::~NativeEglPresenter() = default;

bool NativeEglPresenter::start(
    JNIEnv* environment,
    jobject surface,
    int viewport_width,
    int viewport_height,
    float target_refresh_rate_hz,
    float active_refresh_rate_hz) {
    return implementation_->start(
        environment,
        surface,
        viewport_width,
        viewport_height,
        target_refresh_rate_hz,
        active_refresh_rate_hz);
}

bool NativeEglPresenter::attach_surface(
    JNIEnv* environment,
    jobject surface,
    int viewport_width,
    int viewport_height,
    float target_refresh_rate_hz,
    float active_refresh_rate_hz) {
    return implementation_->attach_surface(
        environment,
        surface,
        viewport_width,
        viewport_height,
        target_refresh_rate_hz,
        active_refresh_rate_hz);
}

void NativeEglPresenter::detach_surface() { implementation_->detach_surface(); }

void NativeEglPresenter::update_display_mode(
    int viewport_width,
    int viewport_height,
    float target_refresh_rate_hz,
    float active_refresh_rate_hz) {
    implementation_->update_display_mode(
        viewport_width,
        viewport_height,
        target_refresh_rate_hz,
        active_refresh_rate_hz);
}

void NativeEglPresenter::submit(
    const std::vector<uint8_t>& framebuffer,
    int framebuffer_width,
    int framebuffer_height,
    int damage_x,
    int damage_y,
    int damage_width,
    int damage_height) {
    implementation_->submit(
        framebuffer,
        framebuffer_width,
        framebuffer_height,
        damage_x,
        damage_y,
        damage_width,
        damage_height);
}

void NativeEglPresenter::stop() { implementation_->stop(); }
bool NativeEglPresenter::ready() const { return implementation_->ready(); }
NativePresentationSnapshot NativeEglPresenter::snapshot() const { return implementation_->snapshot(); }
std::string NativeEglPresenter::detail() const { return implementation_->detail(); }
