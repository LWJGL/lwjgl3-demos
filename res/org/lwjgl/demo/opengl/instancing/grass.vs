/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110
#extension GL_NV_non_square_matrices : enable

attribute vec3 position;
attribute vec2 texCoord;
attribute vec2 displacement;
attribute vec4 worldPosition;
attribute vec4 rotation;

uniform mat4 vpMatrix;

varying vec2 texCoordVarying;
varying float yVarying;
varying vec3 normalVarying;
varying float texIndexVarying;

void main(void) {
  texCoordVarying = texCoord;
  texIndexVarying = worldPosition.w * worldPosition.w * 4.0;
  yVarying = position.y;
  vec3 spos = position;
  spos.y *= worldPosition.z;
  vec2 pos = spos.xz;
  mat2x2 rot = mat2x2(rotation.x, rotation.y, rotation.z, rotation.w);
  pos = rot * pos;
  pos += displacement * yVarying;
  pos += worldPosition.xy;
  normalVarying = vec3(0.0, 1.0, 0.0);
  normalVarying.xz += displacement * yVarying;
  gl_Position = vpMatrix * vec4(pos.x, spos.y, pos.y, 1.0);
}
