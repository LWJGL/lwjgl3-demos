/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_EXT_ray_tracing : enable
#extension GL_EXT_buffer_reference : enable
#extension GL_EXT_scalar_block_layout : enable
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : enable

struct hitPayload
{
  vec3 col;
  float t;
  vec3 n;
  uint mat;
};
layout(location = 0) rayPayloadInEXT hitPayload payload;

struct InstanceDesc {
  uint64_t vertexAddress;
  uint64_t indexAddress;
};
layout(buffer_reference) buffer PositionsAndTypes {uint pt[]; };
layout(buffer_reference, scalar) buffer Indices {uvec3 i[]; };
layout(binding = 3, set = 0) buffer InstancesDescs { InstanceDesc id[]; } instancesDescs;
layout(binding = 4, set = 0) buffer Materials { uint m[]; } materials;

void main(void) {
  InstanceDesc instanceDesc = instancesDescs.id[gl_InstanceCustomIndexEXT];
  Indices  indices  = Indices(instanceDesc.indexAddress);
  PositionsAndTypes positionsAndTypes = PositionsAndTypes(instanceDesc.vertexAddress);
  uvec3 ind = indices.i[gl_PrimitiveID];
  uint pt0 = positionsAndTypes.pt[ind.x],
       pt1 = positionsAndTypes.pt[ind.y],
       pt2 = positionsAndTypes.pt[ind.z];
  vec3 p0 = vec3(pt0 & 0xFFu, pt0 >> 8u & 0xFFu, pt0 >> 16u & 0xFFu),
       p1 = vec3(pt1 & 0xFFu, pt1 >> 8u & 0xFFu, pt1 >> 16u & 0xFFu),
       p2 = vec3(pt2 & 0xFFu, pt2 >> 8u & 0xFFu, pt2 >> 16u & 0xFFu);
  uint type = pt0 >> 24u & 0xFFu;
  payload.col = unpackUnorm4x8(materials.m[type]).rgb;
  payload.n = normalize(cross(p1-p0, p2-p0));
  payload.mat = type;
  payload.t = gl_RayTmaxEXT;
}
