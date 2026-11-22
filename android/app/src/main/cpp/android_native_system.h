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

#pragma once

#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include "native_system.h"

#include <atomic>

struct ANativeWindow;

class AndroidNativeSystem : public NativeSystem
{
public:
    // A non-positive width/height means "use the size of the window".
    // Otherwise the swapchain images use the requested size and are
    // scaled to the window by the compositor.
    AndroidNativeSystem(ANativeWindow* window, int width, int height,
                        std::atomic<bool> const& quit);
    ~AndroidNativeSystem();

    VulkanWSI::Extensions required_extensions() override;
    uint32_t get_presentation_queue_family_index(vk::PhysicalDevice const& pd) override;
    bool should_quit() override;
    vk::Extent2D get_vk_extent() override;
    ManagedResource<vk::SurfaceKHR> create_vk_surface(VulkanState& vulkan) override;

private:
    ANativeWindow* const window;
    int const requested_width;
    int const requested_height;
    std::atomic<bool> const& quit;
};
