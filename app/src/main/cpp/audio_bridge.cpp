#include "audio_bridge.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <utility>

namespace {

constexpr int64_t kStreamTransitionTimeoutNanoseconds = 200'000'000;
constexpr useconds_t kWorkerPollMicroseconds = 10'000;

bool is_output_failure(AudioBridgeFailure failure) {
    return failure == AudioBridgeFailure::PlaybackOpenFailed ||
        failure == AudioBridgeFailure::PlaybackDisconnected;
}

bool is_input_failure(AudioBridgeFailure failure) {
    return failure == AudioBridgeFailure::MicrophoneOpenFailed ||
        failure == AudioBridgeFailure::MicrophoneDisconnected;
}

}  // namespace

AudioBridge::AudioBridge() = default;

AudioBridge::~AudioBridge() {
    stop();
}

bool AudioBridge::prepare(const std::string& runtime_directory) {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    return !running_.load(std::memory_order_acquire) && runtime_directory_fd_ < 0 &&
        create_transport(runtime_directory);
}

bool AudioBridge::start() {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    if (running_.load(std::memory_order_acquire) || runtime_directory_fd_ < 0 ||
        playback_fd_ < 0 || microphone_fd_ < 0) {
        return false;
    }

    playback_buffer_.clear_and_zero();
    microphone_buffer_.clear_and_zero();
    playback_requested_.store(false, std::memory_order_release);
    playback_audible_.store(false, std::memory_order_release);
    microphone_enabled_.store(false, std::memory_order_release);
    output_disconnected_.store(false, std::memory_order_release);
    input_disconnected_.store(false, std::memory_order_release);
    failure_.store(static_cast<int64_t>(AudioBridgeFailure::None), std::memory_order_release);
    underrun_count_.store(0, std::memory_order_release);
    overflow_count_.store(0, std::memory_order_release);
    running_.store(true, std::memory_order_release);
    try {
        playback_thread_ = std::thread(&AudioBridge::playback_worker, this);
        microphone_thread_ = std::thread(&AudioBridge::microphone_worker, this);
    } catch (...) {
        running_.store(false, std::memory_order_release);
        if (playback_thread_.joinable()) playback_thread_.join();
        set_error(AudioBridgeFailure::TransportUnavailable, "The audio workers could not start");
        return false;
    }
    return true;
}

void AudioBridge::stop() {
    {
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        if (!running_.exchange(false, std::memory_order_acq_rel) && runtime_directory_fd_ < 0) return;
        microphone_enabled_.store(false, std::memory_order_release);
        playback_audible_.store(false, std::memory_order_release);
        close_input_stream_locked();
        close_output_stream_locked();
    }
    if (playback_thread_.joinable()) playback_thread_.join();
    if (microphone_thread_.joinable()) microphone_thread_.join();

    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    playback_buffer_.clear_and_zero();
    microphone_buffer_.clear_and_zero();
    if (playback_fd_ >= 0) close(playback_fd_);
    if (microphone_fd_ >= 0) close(microphone_fd_);
    playback_fd_ = -1;
    microphone_fd_ = -1;
    if (runtime_directory_fd_ >= 0) {
        unlinkat(runtime_directory_fd_, audio_config::kPlaybackFileName, 0);
        unlinkat(runtime_directory_fd_, audio_config::kMicrophoneFileName, 0);
        close(runtime_directory_fd_);
        runtime_directory_fd_ = -1;
    }
    playback_requested_.store(false, std::memory_order_release);
    output_device_id_.store(AAUDIO_UNSPECIFIED, std::memory_order_release);
    input_device_id_.store(AAUDIO_UNSPECIFIED, std::memory_order_release);
}

bool AudioBridge::set_playback_audible(bool enabled) {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    if (!running_.load(std::memory_order_acquire)) return false;
    playback_audible_.store(enabled, std::memory_order_release);
    if (!enabled) {
        close_output_stream_locked();
        return true;
    }
    if (output_stream_ != nullptr) return true;
    return open_output_stream_locked();
}

bool AudioBridge::set_microphone_enabled(bool enabled) {
    {
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        if (!running_.load(std::memory_order_acquire)) return false;
        microphone_enabled_.store(false, std::memory_order_release);
        close_input_stream_locked();
    }

    const int64_t reset = microphone_reset_requested_.fetch_add(1, std::memory_order_acq_rel) + 1;
    for (int attempt = 0; attempt < 20; ++attempt) {
        if (microphone_reset_completed_.load(std::memory_order_acquire) >= reset) break;
        usleep(kWorkerPollMicroseconds);
    }
    if (!enabled) return true;

    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    if (!running_.load(std::memory_order_acquire)) return false;
    microphone_enabled_.store(true, std::memory_order_release);
    if (!open_input_stream_locked()) {
        microphone_enabled_.store(false, std::memory_order_release);
        return false;
    }
    return true;
}

AudioBridgeSnapshot AudioBridge::snapshot() const {
    const auto failure = static_cast<AudioBridgeFailure>(failure_.load(std::memory_order_acquire));
    AudioPlaybackStatus playback = AudioPlaybackStatus::Unavailable;
    AudioMicrophoneStatus microphone = AudioMicrophoneStatus::Off;
    if (running_.load(std::memory_order_acquire)) {
        if (is_output_failure(failure) && !output_active_.load(std::memory_order_acquire)) {
            playback = AudioPlaybackStatus::Failed;
        } else if (!playback_requested_.load(std::memory_order_acquire)) {
            playback = AudioPlaybackStatus::Idle;
        } else if (playback_audible_.load(std::memory_order_acquire) &&
            output_active_.load(std::memory_order_acquire)) {
            playback = AudioPlaybackStatus::Playing;
        } else {
            playback = AudioPlaybackStatus::WaitingForFocus;
        }

        if (microphone_enabled_.load(std::memory_order_acquire) &&
            input_active_.load(std::memory_order_acquire)) {
            microphone = AudioMicrophoneStatus::Active;
        } else if (is_input_failure(failure)) {
            microphone = AudioMicrophoneStatus::Failed;
        }
    }
    return AudioBridgeSnapshot{
        playback,
        microphone,
        failure,
        output_device_id_.load(std::memory_order_acquire),
        input_device_id_.load(std::memory_order_acquire),
        underrun_count_.load(std::memory_order_acquire),
        overflow_count_.load(std::memory_order_acquire),
    };
}

std::string AudioBridge::last_error() const {
    std::lock_guard<std::mutex> lock(error_mutex_);
    return last_error_;
}

aaudio_data_callback_result_t AudioBridge::output_callback(
    AAudioStream*, void* user_data, void* audio_data, int32_t frame_count) {
    auto* bridge = static_cast<AudioBridge*>(user_data);
    auto* samples = static_cast<int16_t*>(audio_data);
    const size_t requested = static_cast<size_t>(frame_count) * audio_config::kPlaybackChannels;
    const size_t copied = bridge->playback_buffer_.pop(samples, requested);
    std::fill(samples + copied, samples + requested, 0);
    if (copied < requested) bridge->underrun_count_.fetch_add(1, std::memory_order_relaxed);
    return bridge->running_.load(std::memory_order_relaxed)
        ? AAUDIO_CALLBACK_RESULT_CONTINUE
        : AAUDIO_CALLBACK_RESULT_STOP;
}

aaudio_data_callback_result_t AudioBridge::input_callback(
    AAudioStream*, void* user_data, void* audio_data, int32_t frame_count) {
    auto* bridge = static_cast<AudioBridge*>(user_data);
    if (!bridge->microphone_enabled_.load(std::memory_order_relaxed)) {
        return bridge->running_.load(std::memory_order_relaxed)
            ? AAUDIO_CALLBACK_RESULT_CONTINUE
            : AAUDIO_CALLBACK_RESULT_STOP;
    }
    const auto* samples = static_cast<const int16_t*>(audio_data);
    const size_t requested = static_cast<size_t>(frame_count) * audio_config::kMicrophoneChannels;
    const size_t copied = bridge->microphone_buffer_.push(samples, requested);
    if (copied < requested) bridge->overflow_count_.fetch_add(1, std::memory_order_relaxed);
    return bridge->running_.load(std::memory_order_relaxed)
        ? AAUDIO_CALLBACK_RESULT_CONTINUE
        : AAUDIO_CALLBACK_RESULT_STOP;
}

void AudioBridge::output_error_callback(AAudioStream*, void* user_data, aaudio_result_t error) {
    if (error != AAUDIO_ERROR_DISCONNECTED) return;
    auto* bridge = static_cast<AudioBridge*>(user_data);
    bridge->output_disconnected_.store(true, std::memory_order_release);
}

void AudioBridge::input_error_callback(AAudioStream*, void* user_data, aaudio_result_t error) {
    if (error != AAUDIO_ERROR_DISCONNECTED) return;
    auto* bridge = static_cast<AudioBridge*>(user_data);
    bridge->input_disconnected_.store(true, std::memory_order_release);
}

bool AudioBridge::create_transport(const std::string& runtime_directory) {
    runtime_directory_fd_ = open(
        runtime_directory.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (runtime_directory_fd_ < 0) {
        set_error(AudioBridgeFailure::TransportUnavailable, "The audio runtime directory is unavailable");
        return false;
    }

    const std::array<const char*, 2> names{
        audio_config::kPlaybackFileName,
        audio_config::kMicrophoneFileName,
    };
    for (const char* name : names) {
        struct stat existing {};
        if (fstatat(runtime_directory_fd_, name, &existing, AT_SYMLINK_NOFOLLOW) == 0 || errno != ENOENT ||
            mkfifoat(runtime_directory_fd_, name, 0600) != 0) {
            set_error(AudioBridgeFailure::TransportUnavailable, "The private audio transport could not be created");
            return false;
        }
    }

    playback_fd_ = openat(
        runtime_directory_fd_, audio_config::kPlaybackFileName,
        O_RDWR | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    microphone_fd_ = openat(
        runtime_directory_fd_, audio_config::kMicrophoneFileName,
        O_RDWR | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    for (const int descriptor : {playback_fd_, microphone_fd_}) {
        struct stat status {};
        if (descriptor < 0 || fstat(descriptor, &status) != 0 || !S_ISFIFO(status.st_mode) ||
            status.st_uid != getuid() || fchmod(descriptor, 0600) != 0) {
            set_error(AudioBridgeFailure::TransportUnavailable, "The private audio transport is not trustworthy");
            return false;
        }
    }
    return true;
}

bool AudioBridge::open_output_stream_locked() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) {
        set_error(AudioBridgeFailure::PlaybackOpenFailed, "Android audio playback is unavailable");
        return false;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, audio_config::kPlaybackSampleRate);
    AAudioStreamBuilder_setChannelCount(builder, audio_config::kPlaybackChannels);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(builder, output_callback, this);
    AAudioStreamBuilder_setErrorCallback(builder, output_error_callback, this);
    const aaudio_result_t open_result = AAudioStreamBuilder_openStream(builder, &output_stream_);
    AAudioStreamBuilder_delete(builder);
    if (open_result != AAUDIO_OK || output_stream_ == nullptr ||
        !valid_stream_config(
            output_stream_, AAUDIO_DIRECTION_OUTPUT,
            audio_config::kPlaybackSampleRate, audio_config::kPlaybackChannels) ||
        AAudioStream_requestStart(output_stream_) != AAUDIO_OK) {
        close_output_stream_locked();
        set_error(AudioBridgeFailure::PlaybackOpenFailed, "Android audio playback could not start");
        return false;
    }
    output_device_id_.store(AAudioStream_getDeviceId(output_stream_), std::memory_order_release);
    output_active_.store(true, std::memory_order_release);
    output_disconnected_.store(false, std::memory_order_release);
    if (is_output_failure(static_cast<AudioBridgeFailure>(failure_.load(std::memory_order_acquire)))) {
        failure_.store(static_cast<int64_t>(AudioBridgeFailure::None), std::memory_order_release);
    }
    return true;
}

bool AudioBridge::open_input_stream_locked() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) {
        set_error(AudioBridgeFailure::MicrophoneOpenFailed, "Android microphone input is unavailable");
        return false;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, audio_config::kMicrophoneSampleRate);
    AAudioStreamBuilder_setChannelCount(builder, audio_config::kMicrophoneChannels);
    AAudioStreamBuilder_setInputPreset(builder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setPrivacySensitive(builder, true);
    AAudioStreamBuilder_setDataCallback(builder, input_callback, this);
    AAudioStreamBuilder_setErrorCallback(builder, input_error_callback, this);
    const aaudio_result_t open_result = AAudioStreamBuilder_openStream(builder, &input_stream_);
    AAudioStreamBuilder_delete(builder);
    if (open_result != AAUDIO_OK || input_stream_ == nullptr ||
        !valid_stream_config(
            input_stream_, AAUDIO_DIRECTION_INPUT,
            audio_config::kMicrophoneSampleRate, audio_config::kMicrophoneChannels) ||
        AAudioStream_requestStart(input_stream_) != AAUDIO_OK) {
        close_input_stream_locked();
        set_error(AudioBridgeFailure::MicrophoneOpenFailed, "Android microphone input could not start");
        return false;
    }
    input_device_id_.store(AAudioStream_getDeviceId(input_stream_), std::memory_order_release);
    input_active_.store(true, std::memory_order_release);
    input_disconnected_.store(false, std::memory_order_release);
    if (is_input_failure(static_cast<AudioBridgeFailure>(failure_.load(std::memory_order_acquire)))) {
        failure_.store(static_cast<int64_t>(AudioBridgeFailure::None), std::memory_order_release);
    }
    return true;
}

void AudioBridge::close_output_stream_locked() {
    if (output_stream_ == nullptr) return;
    AAudioStream_requestStop(output_stream_);
    aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNKNOWN;
    AAudioStream_waitForStateChange(
        output_stream_, AAUDIO_STREAM_STATE_STOPPING, &next, kStreamTransitionTimeoutNanoseconds);
    AAudioStream_close(output_stream_);
    output_stream_ = nullptr;
    output_active_.store(false, std::memory_order_release);
    output_device_id_.store(AAUDIO_UNSPECIFIED, std::memory_order_release);
}

void AudioBridge::close_input_stream_locked() {
    if (input_stream_ == nullptr) return;
    AAudioStream_requestStop(input_stream_);
    aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNKNOWN;
    AAudioStream_waitForStateChange(
        input_stream_, AAUDIO_STREAM_STATE_STOPPING, &next, kStreamTransitionTimeoutNanoseconds);
    AAudioStream_close(input_stream_);
    input_stream_ = nullptr;
    input_active_.store(false, std::memory_order_release);
    input_device_id_.store(AAUDIO_UNSPECIFIED, std::memory_order_release);
}

void AudioBridge::playback_worker() {
    std::array<int16_t, 4096> samples{};
    auto last_data = std::chrono::steady_clock::now();
    while (running_.load(std::memory_order_acquire)) {
        const ssize_t count = read(playback_fd_, samples.data(), sizeof(samples));
        if (count > 0) {
            const size_t sample_count = static_cast<size_t>(count) / sizeof(int16_t);
            const size_t accepted = playback_buffer_.push(samples.data(), sample_count);
            if (accepted < sample_count || (count % static_cast<ssize_t>(sizeof(int16_t))) != 0) {
                overflow_count_.fetch_add(1, std::memory_order_relaxed);
            }
            last_data = std::chrono::steady_clock::now();
            playback_requested_.store(true, std::memory_order_release);
        } else if (count < 0 && errno != EAGAIN && errno != EINTR) {
            set_error(AudioBridgeFailure::TransportUnavailable, "The guest playback transport failed");
        }
        if (std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - last_data).count() >=
            audio_config::kPlaybackIdleTimeoutMs) {
            playback_requested_.store(false, std::memory_order_release);
        }
        recover_disconnected_streams();
        usleep(kWorkerPollMicroseconds);
    }
    std::fill(samples.begin(), samples.end(), 0);
}

void AudioBridge::microphone_worker() {
    constexpr size_t kSamplesPerTick =
        static_cast<size_t>(audio_config::kMicrophoneSampleRate) *
        audio_config::kMicrophoneChannels / 100U;
    std::array<int16_t, kSamplesPerTick> samples{};
    std::array<uint8_t, 4096> drain{};
    while (running_.load(std::memory_order_acquire)) {
        const int64_t requested = microphone_reset_requested_.load(std::memory_order_acquire);
        if (requested > microphone_reset_completed_.load(std::memory_order_relaxed)) {
            microphone_buffer_.clear_and_zero();
            while (read(microphone_fd_, drain.data(), drain.size()) > 0) {
                std::fill(drain.begin(), drain.end(), 0);
            }
            microphone_reset_completed_.store(requested, std::memory_order_release);
        }

        size_t copied = 0;
        if (microphone_enabled_.load(std::memory_order_acquire)) {
            copied = microphone_buffer_.pop(samples.data(), samples.size());
        }
        std::fill(samples.begin() + static_cast<ptrdiff_t>(copied), samples.end(), 0);
        const ssize_t written = write(microphone_fd_, samples.data(), sizeof(samples));
        if (written < 0 && errno != EAGAIN && errno != EINTR) {
            set_error(AudioBridgeFailure::TransportUnavailable, "The guest microphone transport failed");
        }
        recover_disconnected_streams();
        usleep(kWorkerPollMicroseconds);
    }
    std::fill(samples.begin(), samples.end(), 0);
    std::fill(drain.begin(), drain.end(), 0);
}

void AudioBridge::recover_disconnected_streams() {
    const bool output_disconnected = output_disconnected_.exchange(false, std::memory_order_acq_rel);
    const bool input_disconnected = input_disconnected_.exchange(false, std::memory_order_acq_rel);
    if (!output_disconnected && !input_disconnected) return;
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    if (!running_.load(std::memory_order_acquire)) return;
    if (output_disconnected && output_stream_ != nullptr) {
        close_output_stream_locked();
        set_error(AudioBridgeFailure::PlaybackDisconnected, "The Android playback route changed");
        if (playback_audible_.load(std::memory_order_acquire)) open_output_stream_locked();
    }
    if (input_disconnected && input_stream_ != nullptr) {
        close_input_stream_locked();
        set_error(AudioBridgeFailure::MicrophoneDisconnected, "The Android microphone route changed");
        if (microphone_enabled_.load(std::memory_order_acquire)) open_input_stream_locked();
    }
}

void AudioBridge::set_error(AudioBridgeFailure failure, std::string message) {
    failure_.store(static_cast<int64_t>(failure), std::memory_order_release);
    std::lock_guard<std::mutex> lock(error_mutex_);
    last_error_ = std::move(message);
}

bool AudioBridge::valid_stream_config(
    AAudioStream* stream, aaudio_direction_t direction, int32_t rate, int32_t channels) {
    return stream != nullptr &&
        AAudioStream_getDirection(stream) == direction &&
        AAudioStream_getFormat(stream) == AAUDIO_FORMAT_PCM_I16 &&
        AAudioStream_getSampleRate(stream) == rate &&
        AAudioStream_getChannelCount(stream) == channels;
}
