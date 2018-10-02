/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

/* This comes interpolated from the vertex shader */
varying vec2 texcoord;

/* The texture we are going to sample */
uniform sampler2D tex;

void main(void) {
  /* Well, simply sample the texture */
  gl_FragColor = texture2D(tex, texcoord);
}
