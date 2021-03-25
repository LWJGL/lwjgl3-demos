/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_tracing : enable
#extension GL_EXT_shader_explicit_arithmetic_types_int16 : enable

struct hitPayload
{
  vec3 col;
  float t;
  vec3 n;
  uint mat;
};
layout(location = 0) rayPayloadInEXT hitPayload payload;

layout(binding = 3, set = 0) buffer PositionsAndTypes { uint16_t pt[]; } positionsAndTypes;
layout(binding = 4, set = 0) buffer Indices { uint16_t i[]; } indices;
layout(binding = 5, set = 0) buffer Materials { uint m[]; } materials;

const vec3 normals[6] = vec3[6](vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0),
                                vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0),
                                vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0));

void main(void) {
  uint index = uint(indices.i[3u * gl_PrimitiveID]);
  uint typeAndSide = positionsAndTypes.pt[4u * index + 3u];
  uint type = typeAndSide & 0xFFu;
  uint side = typeAndSide >> 8u;
  vec3 col = unpackUnorm4x8(materials.m[type]).rgb;
  payload.col = col;
  payload.mat = type ;
  payload.n = normals[side];
  payload.t = gl_RayTmaxEXT;
}
