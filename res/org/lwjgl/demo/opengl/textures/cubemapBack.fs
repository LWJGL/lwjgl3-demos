/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform samplerCube tex;

varying vec3 dir;

void main(void) {
  gl_FragColor = textureCube(tex, dir);
}
