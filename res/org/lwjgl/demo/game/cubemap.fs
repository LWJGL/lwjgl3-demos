/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
#version 110

uniform samplerCube tex;

varying vec3 dir;

void main(void) {
  gl_FragColor = vec4(textureCube(tex, dir).rgb * 1.5, 1.0);
  gl_FragDepth = 0.9999999;
}
