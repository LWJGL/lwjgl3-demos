/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core
#extension GL_ARB_shader_storage_buffer_object : enable

layout(std430, binding = 0) writeonly restrict buffer VisibleChunksBuffer {
  uint visibles[];
};

layout(std140) uniform Uniforms {
  mat4 mvp;
  ivec4 camPos;
};

// X = chunk X coordinate
// Y = chunk Z coordinate
// Z = chunk minY | maxY << 16
// W = index | (1 if chunk MUST be drawn, 0 otherwise) << 31
layout(location=0) in ivec4 chunk;

out VS_OUT {
  ivec4 chunk;
} vs_out;

void main(void) {
  vs_out.chunk = chunk;
  if (chunk.w < 0) {
    visibles[chunk.w & 0xFFFFFF] = 1u;
  }
  int minY = int(chunk.z & 0xFFFF);
  gl_Position = mvp * vec4(vec3(ivec3(chunk.x, minY, chunk.y) - camPos.xyz), 1.0);
}
