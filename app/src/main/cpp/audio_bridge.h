#pragma once

#include <aaudio/AAudio.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "audio_config.h"
#include "audio_ring_buffer.h"

enum class AudioPlaybackStatus : int64_t {
    Unavailable = 0,
    Idle = 1,
    WaitingForFocus = 2,
    Playing = 3,
    Failed = 4,
};

enum class AudioMicrophoneStatus : int64_t {
    Off = 0,
    Active = 1,
    Failed = 2,
};

enum class AudioBridgeFailure : int64_t {
    None = 0,
    TransportUnavailable = 1,
    PlaybackOpenFailed = 2,
    PlaybackDisconnected = 3,
    MicrophoneOpenFailed = 4,
    MicrophoneDisconnected = 5,
};

struct AudioBridgeSnapshot {
    AudioPlaybackStatus playback_status;
    AudioMicrophoneStatus microphone_status;
    AudioBridgeFailure failure;
    int32_t output_device_id;
    int32_t input_device_id;
    uint64_t underrun_count;
    uint64_t overflow_count;
};

class AudioBridge {
public:
    AudioBridge();
    ~AudioBridge();

    AudioBridge(const AudioBridge&) = delete;
    AudioBridge& operator=(const AudioBridge&) = delete;

    bool prepare(const std::string& runtime_directory);
    bool start();
    void stop();
    bool set_playback_audible(bool enabled);
    bool set_microphone_enabled(bool enabled);
    AudioBridgeSnapshot snapshot() const;
    std::string last_error() const;

private:
    static aaudio_data_callback_result_t output_callback(
        AAudioStream*, void* user_data, void* audio_data, int32_t frame_count);
    static aaudio_data_callback_result_t input_callback(
        AAudioStream*, void* user_data, void* audio_data, int32_t frame_count);
    static void output_error_callback(AAudioStream*, void* user_data, aaudio_result_t error);
    static void input_error_callback(AAudioStream*, void* user_data, aaudio_result_t error);

    bool create_transport(const std::string& runtime_directory);
    bool open_output_stream_locked();
    bool open_input_stream_locked();
    void close_output_stream_locked();
    void close_input_stream_locked();
    void playback_worker();
    void microphone_worker();
    void recover_disconnected_streams();
    void set_error(AudioBridgeFailure failure, std::string message);
    static bool valid_stream_config(
        AAudioStream* stream, aaudio_direction_t direction, int32_t rate, int32_t channels);

    AudioRingBuffer<int16_t> playback_buffer_{audio_config::kPlaybackBufferSamples};
    AudioRingBuffer<int16_t> microphone_buffer_{audio_config::kMicrophoneBufferSamples};
    mutable std::mutex lifecycle_mutex_;
    mutable std::mutex error_mutex_;
    AAudioStream* output_stream_ = nullptr;
    AAudioStream* input_stream_ = nullptr;
    int runtime_directory_fd_ = -1;
    int playback_fd_ = -1;
    int microphone_fd_ = -1;
    std::thread playback_thread_;
    std::thread microphone_thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> playback_requested_{false};
    std::atomic<bool> playback_audible_{false};
    std::atomic<bool> microphone_enabled_{false};
    std::atomic<bool> output_disconnected_{false};
    std::atomic<bool> input_disconnected_{false};
    std::atomic<bool> output_active_{false};
    std::atomic<bool> input_active_{false};
    std::atomic<int64_t> failure_{static_cast<int64_t>(AudioBridgeFailure::None)};
    std::atomic<int32_t> output_device_id_{AAUDIO_UNSPECIFIED};
    std::atomic<int32_t> input_device_id_{AAUDIO_UNSPECIFIED};
    std::atomic<uint64_t> underrun_count_{0};
    std::atomic<uint64_t> overflow_count_{0};
    std::atomic<int64_t> microphone_reset_requested_{0};
    std::atomic<int64_t> microphone_reset_completed_{0};
    std::string last_error_;
};
