/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_tracing : enable

layout(location = 0) rayPayloadEXT bool payload;
layout(binding = 0, set = 0) uniform accelerationStructureEXT acc;
layout(binding = 1, set = 0, rgba8) uniform image2D image;
layout(binding = 2, set = 0) uniform Camera {
  mat4 mvp;
  mat4 projInverse;
  mat4 viewInverse;
} cam;
layout(binding = 3, set = 0) buffer Materials { uint m[]; } materials;
layout(binding = 4, set = 0) uniform sampler2D depthImage;
layout(binding = 5, set = 0) uniform sampler2D normalAndTypeImage;

/**
 * Decode a float stored in an 8-bit SNORM format
 * to an uint in the range [0..255].
 */
uint snorm8toUint(float snorm8) {
  return uint((snorm8 + 1.0) * 127.0);
}

void main(void) {
  vec2  px        = vec2(gl_LaunchIDEXT.xy) + vec2(0.5);
  vec2  tex       = px / vec2(gl_LaunchSizeEXT.xy);
  vec2  ndc       = tex * 2.0 - vec2(1.0);
  float z         = textureLod(depthImage, tex, 0.0).r;
  vec4  vc        = cam.projInverse * vec4(ndc, z, 1.0);
  vec3  direction = (cam.viewInverse * vec4(vc.xyz, 0.0)).xyz;
  vec3  origin    = cam.viewInverse[3].xyz + direction / vc.w;
  uint  rayFlags  = gl_RayFlagsOpaqueEXT |
                    gl_RayFlagsCullBackFacingTrianglesEXT |
                    gl_RayFlagsSkipClosestHitShaderEXT |
                    gl_RayFlagsTerminateOnFirstHitEXT;
  float tMin      = 0.0;
  float tMax      = 1E+4;
  vec4 normalAndType = textureLod(normalAndTypeImage, tex, 0.0);
  vec3 normal = normalAndType.xyz;
  uint type = snorm8toUint(normalAndType.w);
  vec3 col = unpackUnorm4x8(materials.m[type]).rgb;
  origin += normal * 1E-4;
  direction = normalize(vec3(-0.7, 0.8, 0.4));
  payload = true;
  traceRayEXT(
    acc,       // acceleration structure
    rayFlags,  // rayFlags
    0xFF,      // cullMask
    0,         // sbtRecordOffset // <- see comment [1] below
    0,         // sbtRecordStride // <- see comment [1] below
    0,         // missIndex
    origin,    // ray origin
    tMin,      // ray min range
    direction, // ray direction
    tMax,      // ray max range
    0          // payload (location = 0)
  );
  col *= (payload ? 0.4 : 1.0) * dot(direction, normal);
  imageStore(image, ivec2(gl_LaunchIDEXT), vec4(col, 1.0));
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