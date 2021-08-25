/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

#define PI 3.14159265359

uniform sampler2D tex;
uniform int clouds;
in vec3 dir;
flat in vec3 dirflat;

out vec4 color;

vec3 palette[3] = vec3[3](
  vec3(0.2, 0.3, 0.9),   // water
  vec3(0.9, 0.7, 0.2),   // sand
  vec3(0.2, 0.65, 0.2)); // grass

void main(void) {
  vec3 c = clouds == 1 ? normalize(dirflat) : normalize(dir);
  vec2 t = vec2(atan(c.z, c.x), acos(c.y)) / vec2(2.0 * PI, PI);
  float n = texture(tex, t).r;
  if (clouds == 0) {
	  color = vec4(mix(palette[0], mix(palette[1], palette[2], step(0.8, n)), step(0.5, n)), 1.0);
	} else {
	  color = vec4(1.0, 1.0, 1.0, n*n);
	}
}
