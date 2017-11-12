/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
/* The texture we are going to sample */
uniform sampler2D tex;

/* This comes interpolated from the vertex shader */
in vec2 texcoord;

out vec4 color;

void main(void) {
  /* Well, simply sample the texture */
  color = texture2D(tex, texcoord);
}
