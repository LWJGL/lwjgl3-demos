/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
uniform sampler2D tex;
in vec2 texcoord;
out vec4 color;
void main(void) {
  color = texture2D(tex, texcoord);
}
