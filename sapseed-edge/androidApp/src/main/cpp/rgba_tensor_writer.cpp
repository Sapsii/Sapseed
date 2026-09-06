#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cmath>

namespace {
constexpr float kInverse255 = 1.0f / 255.0f;
constexpr float kPadding = 114.0f / 255.0f;

inline void rotated_to_source(
    int rotation,
    int image_width,
    int image_height,
    int rotated_x,
    int rotated_y,
    int& source_x,
    int& source_y
) {
    switch (rotation) {
        case 0:
            source_x = rotated_x;
            source_y = rotated_y;
            break;
        case 90:
            source_x = rotated_y;
            source_y = image_height - 1 - rotated_x;
            break;
        case 180:
            source_x = image_width - 1 - rotated_x;
            source_y = image_height - 1 - rotated_y;
            break;
        default:
            source_x = image_width - 1 - rotated_y;
            source_y = rotated_x;
            break;
    }
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_sapsii_sapseed_benchmark_NativeRgbaTensorWriter_nativeWrite(
    JNIEnv* env,
    jobject,
    jobject source_buffer,
    jint source_width,
    jint source_height,
    jint source_row_stride,
    jint source_pixel_stride,
    jint rotation_degrees,
    jobject target_buffer,
    jint input_size,
    jboolean channels_first
) {
    auto* source = static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(source_buffer));
    auto* target = static_cast<float*>(env->GetDirectBufferAddress(target_buffer));
    if (source == nullptr || target == nullptr) {
        jclass exception = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(exception, "Native RGBA preprocessing requires direct byte buffers");
        return;
    }

    const bool rotated = rotation_degrees == 90 || rotation_degrees == 270;
    const int rotated_width = rotated ? source_height : source_width;
    const int rotated_height = rotated ? source_width : source_height;
    const float scale = std::min(
        static_cast<float>(input_size) / static_cast<float>(rotated_width),
        static_cast<float>(input_size) / static_cast<float>(rotated_height)
    );
    const int scaled_width = static_cast<int>(rotated_width * scale);
    const int scaled_height = static_cast<int>(rotated_height * scale);
    const float pad_x = (input_size - scaled_width) / 2.0f;
    const float pad_y = (input_size - scaled_height) / 2.0f;
    const int pad_left = static_cast<int>(std::ceil(pad_x));
    const int pad_top = static_cast<int>(std::ceil(pad_y));
    const int pad_right = std::min(input_size, static_cast<int>(std::ceil(pad_x + scaled_width)));
    const int pad_bottom = std::min(input_size, static_cast<int>(std::ceil(pad_y + scaled_height)));
    const int pixel_count = input_size * input_size;

    std::fill(target, target + pixel_count * 3, kPadding);

    for (int output_y = pad_top; output_y < pad_bottom; ++output_y) {
        const int rotated_y = std::clamp(
            static_cast<int>((output_y - pad_y) / scale),
            0,
            rotated_height - 1
        );
        for (int output_x = pad_left; output_x < pad_right; ++output_x) {
            const int rotated_x = std::clamp(
                static_cast<int>((output_x - pad_x) / scale),
                0,
                rotated_width - 1
            );
            int source_x;
            int source_y;
            rotated_to_source(
                rotation_degrees,
                source_width,
                source_height,
                rotated_x,
                rotated_y,
                source_x,
                source_y
            );
            const auto* pixel = source + source_y * source_row_stride + source_x * source_pixel_stride;
            const int output_index = output_y * input_size + output_x;
            const float red = pixel[1] * kInverse255;
            const float green = pixel[2] * kInverse255;
            const float blue = pixel[3] * kInverse255;
            if (channels_first) {
                target[output_index] = red;
                target[pixel_count + output_index] = green;
                target[pixel_count * 2 + output_index] = blue;
            } else {
                const int base = output_index * 3;
                target[base] = red;
                target[base + 1] = green;
                target[base + 2] = blue;
            }
        }
    }
}
