/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform sampler2D tex;
uniform mat4 inverseMatrix;

varying vec2 vertexNDC;
varying vec2 texcoord;

void main(void) {
  float depth = texture2D(tex, texcoord).r;
  vec4 ndc = vec4(vertexNDC, depth * 2.0 - 1.0, 1.0);
  vec4 worldPos = inverseMatrix * ndc;
  worldPos /= worldPos.w;
  gl_FragColor = worldPos;
}
