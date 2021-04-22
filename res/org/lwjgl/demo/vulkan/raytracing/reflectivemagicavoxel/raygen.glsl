/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_tracing : enable

#define WINDOW_MATERIAL_INDEX 79

struct hitPayload
{
  vec3 col;
  float t;
  vec3 n;
  uint mat;
};
layout(location = 0) rayPayloadEXT hitPayload payload;
layout(binding = 0, set = 0) uniform accelerationStructureEXT acc;
layout(binding = 1, set = 0, rgba8) uniform image2D image;
layout(binding = 2, set = 0) uniform Camera {
  vec3 corners[4];
  mat4 viewInverse;
} cam;

void main(void) {
  vec2  px        = vec2(gl_LaunchIDEXT.xy) + vec2(0.5);
  vec2  p         = px / vec2(gl_LaunchSizeEXT.xy);
  vec3  origin    = cam.viewInverse[3].xyz;
  vec3  target    = mix(mix(cam.corners[0], cam.corners[2], p.y), mix(cam.corners[1], cam.corners[3], p.y), p.x);
  vec4  direction = cam.viewInverse * vec4(target.xyz, 0.0);
  uint  rayFlags  = gl_RayFlagsOpaqueEXT | gl_RayFlagsCullBackFacingTrianglesEXT;
  float tMin      = 1E-4;
  float tMax      = 1E+4;
  for (int i = 0; i < 2; i++) {
    traceRayEXT(
      acc,           // acceleration structure
      rayFlags,      // rayFlags
      0xFF,          // cullMask
      0,             // sbtRecordOffset // <- see comment [1] below
      0,             // sbtRecordStride // <- see comment [1] below
      0,             // missIndex
      origin,        // ray origin
      tMin,          // ray min range
      direction.xyz, // ray direction
      tMax,          // ray max range
      0              // payload (location = 0)
    );
    if (payload.mat == WINDOW_MATERIAL_INDEX) {
      origin += payload.t * direction.xyz + payload.n * 1E-5;
      direction.xyz = reflect(direction.xyz, payload.n);
    } else {
      break;
    }
  }
  imageStore(image, ivec2(gl_LaunchIDEXT), vec4(payload.col, 1.0));
}

/*
 * [1]: The formula to determine the hit shader binding table record to invoke is based on the sbt offset and stride
 *      given to vkCmdTraceRaysKHR() call (sbt.offset and sbt.stride) as well the BLAS instance's SBT offset
 *      (VkAccelerationStructureInstanceKHR.instanceShaderBindingTableRecordOffset) and the geometryIndex inside one BLAS
 *      as well as the sbtRecordOffset and sbtRecordStride arguments to traceRayEXT(), in the following way:
 *
 *      hitShaderAddress = sbt.offset + sbt.stride * ( instance.sbtOffset + geometryIndex * sbtRecordStride + sbtRecordOffset )
 *
 *      Reference: https://vulkan.lunarg.com/doc/view/1.2.135.0/windows/chunked_spec/chap35.html#_hit_shaders
 */