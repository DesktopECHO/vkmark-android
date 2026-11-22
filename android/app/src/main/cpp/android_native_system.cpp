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

#include "vulkan_state.h"

#include <android/native_window.h>

#include <stdexcept>

AndroidNativeSystem::AndroidNativeSystem(
    ANativeWindow* window, int width, int height, std::atomic<bool> const& quit)
    : window{window},
      requested_width{width},
      requested_height{height},
      quit{quit}
{
    ANativeWindow_acquire(window);
}

AndroidNativeSystem::~AndroidNativeSystem()
{
    ANativeWindow_release(window);
}

VulkanWSI::Extensions AndroidNativeSystem::required_extensions()
{
    return {{VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME}, {}};
}

uint32_t AndroidNativeSystem::get_presentation_queue_family_index(vk::PhysicalDevice const& pd)
{
    // On Android all queue families support presentation, so prefer
    // a graphics one to avoid needing a separate presentation queue.
    auto const queue_families = pd.getQueueFamilyProperties();

    for (auto i = 0u; i < queue_families.size(); ++i)
    {
        if (queue_families[i].queueCount > 0 &&
            (queue_families[i].queueFlags & vk::QueueFlagBits::eGraphics))
        {
            return i;
        }
    }

    for (auto i = 0u; i < queue_families.size(); ++i)
    {
        if (queue_families[i].queueCount > 0)
            return i;
    }

    return invalid_queue_family_index;
}

bool AndroidNativeSystem::should_quit()
{
    return quit;
}

vk::Extent2D AndroidNativeSystem::get_vk_extent()
{
    if (requested_width > 0 && requested_height > 0)
    {
        return {static_cast<uint32_t>(requested_width),
                static_cast<uint32_t>(requested_height)};
    }

    auto const width = ANativeWindow_getWidth(window);
    auto const height = ANativeWindow_getHeight(window);

    if (width <= 0 || height <= 0)
        throw std::runtime_error{"Failed to get the size of the native window"};

    return {static_cast<uint32_t>(width), static_cast<uint32_t>(height)};
}

ManagedResource<vk::SurfaceKHR> AndroidNativeSystem::create_vk_surface(VulkanState& vulkan)
{
    auto const create_info = vk::AndroidSurfaceCreateInfoKHR{}
        .setWindow(window);

    return ManagedResource<vk::SurfaceKHR>{
        vulkan.instance().createAndroidSurfaceKHR(create_info),
        [vptr = &vulkan] (vk::SurfaceKHR& s) { vptr->instance().destroySurfaceKHR(s); }};
}
