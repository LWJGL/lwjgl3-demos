/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core
#pragma {{DEFINES}}

layout(points) in;
layout(triangle_strip, max_vertices = 14) out;

layout(std140) uniform Uniforms {
  mat4 mvp;
  ivec4 camPos;
};

in VS_OUT {
  ivec4 chunk;
} gs_in[];

out GS_OUT {
  flat int chunkId_out;
} gs_out;

void main() {
    vec4 c = gl_in[0].gl_Position;
    int minY = gs_in[0].chunk.z&0xFFFF;
    int maxY = gs_in[0].chunk.z >> 16 & 0xFFFF;
    vec4 dx = mvp[0]*CHUNK_SIZE, dy = mvp[1] * (maxY - minY + 1), dz = mvp[2]*CHUNK_SIZE;
    vec4 v0=c, v1=c+dx, v2=c+dy, v3=v1+dy, v4=v0+dz, v5=v1+dz, v6=v2+dz, v7=v3+dz;
    gs_out.chunkId_out = gs_in[0].chunk.w & 0xFFFFFF;
#define emit(p) gl_Position=p;EmitVertex();
    emit(v6);emit(v7);emit(v4);emit(v5);emit(v1);emit(v7);emit(v3);
    emit(v6);emit(v2);emit(v4);emit(v0);emit(v1);emit(v2);emit(v3);
#undef emit
}
