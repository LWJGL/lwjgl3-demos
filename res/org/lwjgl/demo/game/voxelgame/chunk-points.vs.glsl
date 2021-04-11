/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

layout(location=0) in uint pu0v0u1v1;
layout(location=1) in uvec4 typeSideAndAoFactors;
layout(location=2) in ivec4 chunkInfo;

out VS_OUT {
  uvec4 typeSideAndAoFactors;
  ivec4 chunkInfo;
  uint pu0v0u1v1;
} vs_out;

void main(void) {
  vs_out.pu0v0u1v1 = pu0v0u1v1;
  vs_out.typeSideAndAoFactors = typeSideAndAoFactors;
  vs_out.chunkInfo = chunkInfo;
}
