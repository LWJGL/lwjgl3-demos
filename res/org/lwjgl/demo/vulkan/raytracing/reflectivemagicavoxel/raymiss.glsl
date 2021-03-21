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
layout(location = 0) rayPayloadInEXT hitPayload payload;

void main(void) {
  payload.mat = 0u;
  payload.col = vec3(0.8, 0.9, 1.0);
}
