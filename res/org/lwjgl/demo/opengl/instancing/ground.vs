/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 110

attribute vec2 position;

uniform mat4 vpMatrix;
uniform float groundSize;

varying vec2 texCoordVarying;

void main(void) {
  texCoordVarying = position * groundSize;
  vec2 pos = position * 2.0 - vec2(1.0, 1.0);
  pos *= groundSize;
  gl_Position = vpMatrix * vec4(pos.x, 0.0, pos.y, 1.0);
}
