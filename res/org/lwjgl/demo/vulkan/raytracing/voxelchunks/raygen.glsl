/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_tracing : enable

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
  vec2 px        = vec2(gl_LaunchIDEXT.xy) + vec2(0.5);
  vec2 p         = px / vec2(gl_LaunchSizeEXT.xy);
  vec3 origin    = cam.viewInverse[3].xyz;
  vec3 target    = mix(mix(cam.corners[0], cam.corners[2], p.y), mix(cam.corners[1], cam.corners[3], p.y), p.x);
  vec4 direction = cam.viewInverse * vec4(target.xyz, 0.0);
  traceRayEXT(acc, 0, 0xFF, 0, 0, 0, origin, 1E-3, direction.xyz, 1E+4, 0);
  imageStore(image, ivec2(gl_LaunchIDEXT), vec4(payload.n, 1.0));
}