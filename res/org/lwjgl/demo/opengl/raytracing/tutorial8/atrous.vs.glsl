/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

uniform sampler2D colorMap;

out vec2 texcoord;

void main(void) {
  vec2 vertex = vec2(-1.0) + vec2(
    float((gl_VertexID & 1) << 2),
    float((gl_VertexID & 2) << 1));
  gl_Position = vec4(vertex, 0.0, 1.0);
  texcoord = (vertex * 0.5 + vec2(0.5)) * textureSize(colorMap, 0);
}
