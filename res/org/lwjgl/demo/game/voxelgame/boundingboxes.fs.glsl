/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core
#extension GL_ARB_shader_storage_buffer_object : enable

layout(early_fragment_tests) in;

layout(std430, binding = 0) writeonly restrict buffer VisibleChunksBuffer {
  uint visibles[];
};

in GS_OUT {
  flat int chunkId_out;
} fs_in;

out vec4 color;

void main(void) {
  visibles[fs_in.chunkId_out] = 1u;
  color = vec4(0.9, 0.3, 0.9, 1.0);
}
