/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 130

in vec3 position;
in vec2 texCoords;
out vec2 texCoordsVarying;

void main() {
  texCoordsVarying = texCoords;
  gl_Position = vec4(position, 1.0);
}
