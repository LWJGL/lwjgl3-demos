/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core
uniform sampler2D tex[2];
uniform sampler2D velocity;
in vec2 texcoord;
out vec4 color;
void main(void) {
  vec2 vel = texture(velocity, texcoord).xy;
  vec4 currColor = texture(tex[0], texcoord);
  vec4 prevColor = texture(tex[1], texcoord-vel);
  color = mix(currColor, prevColor, 0.96);
}
