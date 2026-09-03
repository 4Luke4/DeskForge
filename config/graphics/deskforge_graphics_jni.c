#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <vulkan/vulkan.h>
#include <jni.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <stdbool.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include "vtest_server.h"
#include "vtest.h"
#include "deskforge_graphics_config.h"

static _Atomic int listener_fd = -1;

JNIEXPORT jstring JNICALL
Java_com_deskforge_app_graphics_GraphicsRendererService_nativeProbe(
   JNIEnv *env, jobject instance, jboolean require_venus);
JNIEXPORT void JNICALL
Java_com_deskforge_app_graphics_GraphicsRendererService_nativePrepare(
   JNIEnv *env, jobject instance, jstring render_server_path);
JNIEXPORT jstring JNICALL
Java_com_deskforge_app_graphics_GraphicsRendererService_nativeRun(
   JNIEnv *env, jobject instance, jint descriptor, jint expected_peer_uid, jboolean enable_venus);
JNIEXPORT void JNICALL
Java_com_deskforge_app_graphics_GraphicsRendererService_nativeStop(JNIEnv *env, jobject instance);

static bool contains_software_renderer(const char *renderer)
{
   static const char *software_names[] = { "swiftshader", "llvmpipe", "softpipe" };
   char normalized[256];
   size_t length = renderer ? strlen(renderer) : 0;
   if (!length || length >= sizeof(normalized))
      return true;
   for (size_t index = 0; index <= length; ++index) {
      const char value = renderer[index];
      normalized[index] = value >= 'A' && value <= 'Z' ? (char)(value + ('a' - 'A')) : value;
   }
   for (size_t index = 0; index < sizeof(software_names) / sizeof(software_names[0]); ++index) {
      if (strstr(normalized, software_names[index]))
         return true;
   }
   return false;
}

JNIEXPORT jstring JNICALL
Java_com_deskforge_app_graphics_GraphicsRendererService_nativeProbe(
   JNIEnv *env, jobject instance, jboolean require_venus)
{
   (void)instance;
   EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
   EGLint major = 0, minor = 0;
   EGLConfig config = NULL;
   EGLint count = 0;
   const EGLint config_attributes[] = {
      EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
      EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE
   };
   const EGLint context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
   const EGLint surface_attributes[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
   EGLContext context = EGL_NO_CONTEXT;
   EGLSurface surface = EGL_NO_SURFACE;
   char response[320] = "fallback:EGL self-test failed";

   if (display == EGL_NO_DISPLAY || !eglInitialize(display, &major, &minor) ||
       !eglBindAPI(EGL_OPENGL_ES_API) ||
       !eglChooseConfig(display, config_attributes, &config, 1, &count) || count != 1)
      goto cleanup;
   context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
   surface = eglCreatePbufferSurface(display, config, surface_attributes);
   if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE ||
       !eglMakeCurrent(display, surface, surface, context))
      goto cleanup;
   const char *renderer = (const char *)glGetString(GL_RENDERER);
   if (contains_software_renderer(renderer))
      snprintf(response, sizeof(response), "software:%s", renderer ? renderer : "unknown renderer");
   else {
      VkApplicationInfo application_info = {
         .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
         .pApplicationName = "DeskForge Venus probe",
         .apiVersion = VK_API_VERSION_1_1,
      };
      VkInstanceCreateInfo create_info = {
         .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
         .pApplicationInfo = &application_info,
      };
      VkInstance instance_handle = VK_NULL_HANDLE;
      bool venus_available = false;
      char vulkan_renderer[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE] = {0};
      if (vkCreateInstance(&create_info, NULL, &instance_handle) == VK_SUCCESS) {
         uint32_t device_count = 0;
         if (vkEnumeratePhysicalDevices(instance_handle, &device_count, NULL) == VK_SUCCESS &&
             device_count > 0 && device_count <= 16) {
            VkPhysicalDevice devices[16];
            if (vkEnumeratePhysicalDevices(instance_handle, &device_count, devices) == VK_SUCCESS) {
               for (uint32_t device_index = 0; device_index < device_count && !venus_available;
                    ++device_index) {
                  VkPhysicalDeviceProperties properties;
                  vkGetPhysicalDeviceProperties(devices[device_index], &properties);
                  if (properties.apiVersion < VK_API_VERSION_1_1)
                     continue;
                  uint32_t extension_count = 0;
                  if (vkEnumerateDeviceExtensionProperties(
                         devices[device_index], NULL, &extension_count, NULL) != VK_SUCCESS ||
                      extension_count == 0 || extension_count > 4096)
                     continue;
                  VkExtensionProperties *extensions =
                     calloc(extension_count, sizeof(VkExtensionProperties));
                  if (!extensions)
                     continue;
                  bool dma_buf = false, drm_modifier = false, foreign_queue = false;
                  if (vkEnumerateDeviceExtensionProperties(
                         devices[device_index], NULL, &extension_count, extensions) == VK_SUCCESS) {
                     for (uint32_t index = 0; index < extension_count; ++index) {
                        const char *name = extensions[index].extensionName;
                        dma_buf |= !strcmp(name, VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME);
                        drm_modifier |= !strcmp(name, VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME);
                        foreign_queue |= !strcmp(name, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
                     }
                  }
                  free(extensions);
                  if (dma_buf && drm_modifier && foreign_queue) {
                     venus_available = true;
                     snprintf(vulkan_renderer, sizeof(vulkan_renderer), "%s", properties.deviceName);
                  }
               }
            }
         }
         vkDestroyInstance(instance_handle, NULL);
      }
      if (venus_available)
         snprintf(response, sizeof(response), "venus:%s", vulkan_renderer);
      else if (require_venus)
         snprintf(response, sizeof(response), "fallback:Required Venus Vulkan extensions are unavailable");
      else
         snprintf(response, sizeof(response), "hardware:%s", renderer);
   }

cleanup:
   if (display != EGL_NO_DISPLAY) {
      eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
      if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
      if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
      eglTerminate(display);
   }
   return (*env)->NewStringUTF(env, response);
}

static bool set_process_limit(int resource, rlim_t value)
{
   struct rlimit limit = { value, value };
   return setrlimit(resource, &limit) == 0;
}

JNIEXPORT void JNICALL
Java_com_deskforge_app_graphics_GraphicsRendererService_nativePrepare(
   JNIEnv *env, jobject instance, jstring render_server_path)
{
   (void)instance;
   if (render_server_path) {
      const char *path = (*env)->GetStringUTFChars(env, render_server_path, NULL);
      if (path) {
         setenv("RENDER_SERVER_EXEC_PATH", path, 1);
         (*env)->ReleaseStringUTFChars(env, render_server_path, path);
      }
   }
   deskforge_vtest_prepare();
}

JNIEXPORT jstring JNICALL
Java_com_deskforge_app_graphics_GraphicsRendererService_nativeRun(
   JNIEnv *env, jobject instance, jint descriptor, jint expected_peer_uid, jboolean enable_venus)
{
   (void)instance;
   if (descriptor < 0 || expected_peer_uid <= 0 ||
       !set_process_limit(RLIMIT_AS, DESKFORGE_ADDRESS_SPACE_BYTES) ||
       !set_process_limit(RLIMIT_NOFILE, DESKFORGE_OPEN_FILES) ||
       !set_process_limit(RLIMIT_CORE, DESKFORGE_CORE_BYTES)) {
      if (descriptor >= 0) close(descriptor);
      return (*env)->NewStringUTF(env, "Renderer process limits could not be applied");
   }
   atomic_store(&listener_fd, descriptor);
   vtest_set_resource_limits(
      DESKFORGE_MAX_RESOURCES, DESKFORGE_MAX_RESOURCE_BYTES,
      DESKFORGE_MAX_AGGREGATE_BYTES);
   const int result = deskforge_vtest_run(
      descriptor, (unsigned)expected_peer_uid, DESKFORGE_MAX_CLIENTS,
      DESKFORGE_MAX_COMMAND_BYTES, enable_venus == JNI_TRUE);
   atomic_store(&listener_fd, -1);
   return (*env)->NewStringUTF(env, result == 0 ? "Renderer stopped" : "Renderer protocol failed");
}

JNIEXPORT void JNICALL
Java_com_deskforge_app_graphics_GraphicsRendererService_nativeStop(JNIEnv *env, jobject instance)
{
   (void)env;
   (void)instance;
   deskforge_vtest_stop();
   const int descriptor = atomic_load(&listener_fd);
   if (descriptor < 0)
      return;
   struct sockaddr_un address;
   socklen_t length = sizeof(address);
   if (getsockname(descriptor, (struct sockaddr *)&address, &length) != 0)
      return;
   const int wake = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
   if (wake >= 0) {
      connect(wake, (struct sockaddr *)&address, length);
      close(wake);
   }
}
