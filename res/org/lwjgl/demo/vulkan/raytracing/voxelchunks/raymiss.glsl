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
  payload.n = vec3(0.0);
}
