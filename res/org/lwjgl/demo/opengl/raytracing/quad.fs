/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#if __VERSION__ >= 130
  #define varying in
  #define texture2D texture

  out vec4 color;
  #define OUT color
#else
  #define OUT gl_FragColor
#endif

/* This comes interpolated from the vertex shader */
varying vec2 texcoord;

/* The texture we are going to sample */
uniform sampler2D tex;

void main(void) {
  /* Well, simply sample the texture */
  OUT = texture2D(tex, texcoord);
}
