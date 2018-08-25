/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 330 core

uniform sampler2D colorMap;

in vec2 vertex;
out vec2 texcoord;

void main(void) {
  gl_Position = vec4(vertex, 0.0, 1.0);
  texcoord = (vertex * 0.5 + vec2(0.5)) * textureSize(colorMap, 0);
}
