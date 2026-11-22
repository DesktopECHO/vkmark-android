/*
 * Copyright © 2026 vkmark contributors
 *
 * This file is part of vkmark.
 *
 * vkmark is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation, either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * vkmark is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with vkmark. If not, see <http://www.gnu.org/licenses/>.
 */

#include "android_native_system.h"
#include "swapchain_window_system.h"

#include "vulkan_state.h"
#include "device_uuid.h"
#include "scene.h"
#include "scene_collection.h"
#include "benchmark_collection.h"
#include "default_benchmarks.h"
#include "options.h"
#include "log.h"
#include "util.h"
#include "main_loop.h"

#include "scenes/clear_scene.h"
#include "scenes/cube_scene.h"
#include "scenes/default_options_scene.h"
#include "scenes/desktop_scene.h"
#include "scenes/effect2d_scene.h"
#include "scenes/shading_scene.h"
#include "scenes/texture_scene.h"
#include "scenes/vertex_scene.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>

#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace
{

char const* const log_tag = "vkmark";

JavaVM* java_vm = nullptr;

// Protects 'activity' and the method ids
std::mutex activity_mutex;
jobject activity = nullptr;
jmethodID on_native_output = nullptr;
jmethodID on_native_finished = nullptr;

std::thread benchmark_thread;
std::atomic<bool> quit{false};

// Written to the output stream to mark the point where the benchmarks
// finished, so that the activity is notified only after it has received all
// the output that precedes it.
char const finished_marker = '\x1e';
std::atomic<int> finished_score{-1};

struct ScopedJNIEnv
{
    ScopedJNIEnv()
    {
        if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        {
            if (java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK)
                attached = true;
            else
                env = nullptr;
        }
    }

    ~ScopedJNIEnv()
    {
        if (attached)
            java_vm->DetachCurrentThread();
    }

    JNIEnv* env = nullptr;
    bool attached = false;
};

void send_output_to_activity(JNIEnv* env, char const* data, size_t size)
{
    std::lock_guard<std::mutex> lock{activity_mutex};

    if (!env || !activity)
        return;

    auto const bytes = env->NewByteArray(size);
    if (bytes)
    {
        env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte const*>(data));
        env->CallVoidMethod(activity, on_native_output, bytes);
        env->DeleteLocalRef(bytes);
    }

    if (env->ExceptionCheck())
        env->ExceptionClear();
}

void notify_finished(JNIEnv* env, int score)
{
    std::lock_guard<std::mutex> lock{activity_mutex};

    if (!env || !activity)
        return;

    env->CallVoidMethod(activity, on_native_finished, score);

    if (env->ExceptionCheck())
        env->ExceptionClear();
}

// vkmark logs to stdout and stderr, which go nowhere on Android, so redirect
// them to a pipe and forward what is written there to logcat and the activity.
void forward_output(int fd)
{
    ScopedJNIEnv jni;
    std::string pending_line;
    char buf[1024];
    ssize_t nread;

    while ((nread = read(fd, buf, sizeof(buf))) > 0)
    {
        ssize_t start = 0;
        for (ssize_t i = 0; i < nread; ++i)
        {
            if (buf[i] == finished_marker)
            {
                if (i > start)
                    send_output_to_activity(jni.env, buf + start, i - start);
                notify_finished(jni.env, finished_score);
                start = i + 1;
            }
        }
        if (nread > start)
            send_output_to_activity(jni.env, buf + start, nread - start);

        for (ssize_t i = 0; i < nread; ++i)
        {
            if (buf[i] == finished_marker)
                continue;

            if (buf[i] == '\n')
            {
                __android_log_write(ANDROID_LOG_INFO, log_tag, pending_line.c_str());
                pending_line.clear();
            }
            else
            {
                pending_line.push_back(buf[i]);
            }
        }
    }
}

void redirect_output_once()
{
    static std::once_flag once;

    std::call_once(once, [] {
        int fds[2];
        if (pipe(fds) != 0)
            return;

        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        dup2(fds[1], STDOUT_FILENO);
        dup2(fds[1], STDERR_FILENO);
        close(fds[1]);

        std::thread{forward_output, fds[0]}.detach();
    });
}

// The upstream device listing needs VK_KHR_display, which Android doesn't
// provide, so list the devices here instead.
void log_all_devices()
{
    auto const app_info = vk::ApplicationInfo{}
        .setPApplicationName("vkmark")
        .setApiVersion(VK_MAKE_VERSION(1, 0, 0));
    auto const create_info = vk::InstanceCreateInfo{}
        .setPApplicationInfo(&app_info);

    auto const instance = ManagedResource<vk::Instance>{
        vk::createInstance(create_info),
        [] (auto& i) { i.destroy(); }};

    auto const physical_devices = instance.raw.enumeratePhysicalDevices();

    for (size_t i = 0; i < physical_devices.size(); ++i)
    {
        auto const props = physical_devices[i].getProperties();

        Log::info("=== Physical Device %zu ===\n", i);
        Log::info("    Vendor ID:      0x%X\n", props.vendorID);
        Log::info("    Device ID:      0x%X\n", props.deviceID);
        Log::info("    Device Name:    %s\n", static_cast<char const*>(props.deviceName));
        Log::info("    Driver Version: %u\n", props.driverVersion);
        Log::info("    Device UUID:    %s\n",
                  static_cast<DeviceUUID>(props.pipelineCacheUUID).representation().data());
    }
}

void populate_scene_collection(SceneCollection& sc)
{
    sc.register_scene(std::make_unique<ClearScene>());
    sc.register_scene(std::make_unique<CubeScene>());
    sc.register_scene(std::make_unique<DefaultOptionsScene>(sc));
    sc.register_scene(std::make_unique<DesktopScene>());
    sc.register_scene(std::make_unique<Effect2DScene>());
    sc.register_scene(std::make_unique<ShadingScene>());
    sc.register_scene(std::make_unique<TextureScene>());
    sc.register_scene(std::make_unique<VertexScene>());
}

// The Android counterpart of main() in src/main.cpp. Returns the score,
// or a negative value if no score was produced.
int run_vkmark(ANativeWindow* window, std::string const& data_dir,
               std::vector<std::string>& args)
try
{
    Options options;
    options.data_dir = data_dir;
    // Use the native size of the window, unless a size is requested
    options.size = {-1, -1};

    std::vector<char*> argv;
    for (auto& arg : args)
        argv.push_back(arg.data());
    argv.push_back(nullptr);

    if (!options.parse_args(args.size(), argv.data()))
        return -1;

    Log::init(args[0], options.show_debug);

    if (options.show_help)
    {
        Log::info("%s", options.help_string().c_str());
        return -1;
    }

    Util::set_data_dir(options.data_dir);

    SceneCollection sc;
    populate_scene_collection(sc);

    BenchmarkCollection bc{sc};

    if (options.list_scenes)
    {
        sc.log_scene_info();
        return -1;
    }

    if (options.list_devices)
    {
        log_all_devices();
        return -1;
    }

    SwapchainWindowSystem ws{
        std::make_unique<AndroidNativeSystem>(
            window, options.size.first, options.size.second, quit),
        options.present_mode,
        options.pixel_format};

    auto&& device_strategy = options.use_device_with_uuid ?
        VulkanState::ChoosePhysicalDeviceStrategy{ChooseByUUIDStrategy{*options.use_device_with_uuid}} :
        VulkanState::ChoosePhysicalDeviceStrategy{ChooseFirstSupportedStrategy{}};
    VulkanState vulkan{ws.vulkan_wsi(), device_strategy, options.show_debug};

    auto const ws_vulkan_deinit = Util::on_scope_exit([&] { ws.deinit_vulkan(); });
    ws.init_vulkan(vulkan);

    Log::info("=======================================================\n");
    Log::info("    vkmark %s\n", VKMARK_VERSION_STR);
    Log::info("=======================================================\n");
    vulkan.log_info();
    Log::info("=======================================================\n");

    if (!options.benchmarks.empty())
        bc.add(options.benchmarks);

    if (!bc.contains_normal_scenes())
        bc.add(DefaultBenchmarks::get());

    MainLoop main_loop{vulkan, ws, bc, options};

    main_loop.run();

    if (quit)
        return -1;

    Log::info("=======================================================\n");
    Log::info("                                   vkmark Score: %u\n",
              main_loop.score());
    Log::info("=======================================================\n");

    return main_loop.score();
}
catch (std::exception const& e)
{
    Log::error("%s\n", e.what());
    return -1;
}

void benchmark_thread_main(ANativeWindow* window, std::string data_dir,
                           std::vector<std::string> args)
{
    auto const score = run_vkmark(window, data_dir, args);

    Log::flush();
    ANativeWindow_release(window);

    // If we were asked to quit, the activity is not interested in the result
    if (!quit)
    {
        finished_score = score;
        fputc(finished_marker, stdout);
    }

    fflush(stdout);
}

void stop_benchmark()
{
    if (benchmark_thread.joinable())
    {
        quit = true;
        benchmark_thread.join();
    }
}

}

extern "C"
{

// The upstream device listing code refers to this function, which the Android
// Vulkan library doesn't provide. It is never called (see log_all_devices()
// above), but it needs to exist for the library to link.
VKAPI_ATTR VkResult VKAPI_CALL vkGetPhysicalDeviceDisplayPropertiesKHR(
    VkPhysicalDevice, uint32_t* property_count, VkDisplayPropertiesKHR*)
{
    *property_count = 0;
    return VK_SUCCESS;
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    java_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_org_vkmark_android_MainActivity_nativeStart(
    JNIEnv* env, jobject thiz, jobject surface, jstring jdata_dir, jobjectArray jargs)
{
    stop_benchmark();

    redirect_output_once();

    {
        std::lock_guard<std::mutex> lock{activity_mutex};
        auto const cls = env->GetObjectClass(thiz);
        on_native_output = env->GetMethodID(cls, "onNativeOutput", "([B)V");
        on_native_finished = env->GetMethodID(cls, "onNativeFinished", "(I)V");
        if (activity)
            env->DeleteGlobalRef(activity);
        activity = env->NewGlobalRef(thiz);
    }

    auto const data_dir_chars = env->GetStringUTFChars(jdata_dir, nullptr);
    std::string data_dir{data_dir_chars};
    env->ReleaseStringUTFChars(jdata_dir, data_dir_chars);

    std::vector<std::string> args{"vkmark"};
    auto const nargs = jargs ? env->GetArrayLength(jargs) : 0;
    for (jsize i = 0; i < nargs; ++i)
    {
        auto const jarg = static_cast<jstring>(env->GetObjectArrayElement(jargs, i));
        auto const chars = env->GetStringUTFChars(jarg, nullptr);
        args.emplace_back(chars);
        env->ReleaseStringUTFChars(jarg, chars);
        env->DeleteLocalRef(jarg);
    }

    // Released by the benchmark thread
    auto const window = ANativeWindow_fromSurface(env, surface);
    if (!window)
    {
        __android_log_write(ANDROID_LOG_ERROR, log_tag, "Failed to get native window");
        return;
    }

    quit = false;
    benchmark_thread = std::thread{
        benchmark_thread_main, window, std::move(data_dir), std::move(args)};
}

JNIEXPORT void JNICALL Java_org_vkmark_android_MainActivity_nativeStop(JNIEnv* env, jobject)
{
    stop_benchmark();

    std::lock_guard<std::mutex> lock{activity_mutex};
    if (activity)
    {
        env->DeleteGlobalRef(activity);
        activity = nullptr;
    }
}

}
