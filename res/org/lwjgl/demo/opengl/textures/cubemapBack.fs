/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 110

uniform samplerCube tex;

varying vec3 dir;

void main(void) {
  gl_FragColor = textureCube(tex, dir);
}
