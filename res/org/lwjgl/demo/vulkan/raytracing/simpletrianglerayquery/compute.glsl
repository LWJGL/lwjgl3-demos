/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_query : enable

layout(binding = 0, set = 0) uniform accelerationStructureEXT acc;
layout(binding = 1, set = 0, rgba8) uniform image2D image;
layout(binding = 2, set = 0) uniform Camera {
  vec3 corners[4];
  mat4 viewInverse;
} cam;

layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

void main(void) {
  ivec2 pixel     = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size      = imageSize(image);
  if (any(greaterThanEqual(pixel, size)))
    return;
  vec2  px        = vec2(pixel) + vec2(0.5);
  vec2  p         = px / vec2(size);
  vec3  origin    = cam.viewInverse[3].xyz;
  vec3  target    = mix(mix(cam.corners[0], cam.corners[2], p.y), mix(cam.corners[1], cam.corners[3], p.y), p.x);
  vec4  direction = cam.viewInverse * vec4(normalize(target.xyz), 0.0);
  rayQueryEXT rayQuery;
  rayQueryInitializeEXT(rayQuery,
                        acc,
                        gl_RayFlagsOpaqueEXT,
                        0xFF,
                        origin,
                        0.1,
                        direction.xyz,
                        100.0);
  while(rayQueryProceedEXT(rayQuery)) {}
  float t = rayQueryGetIntersectionTEXT(rayQuery, true);
  imageStore(image, pixel, 
    t < 100.0
      ? vec4(0.5, 0.6, 0.7, 1.0)
      : vec4(0.2, 0.3, 0.4, 1.0));
}
