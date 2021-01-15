/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core
#pragma {{DEFINES}}

layout(binding=0) uniform usampler2D tex;
in vec2 texcoord;
out vec4 color;

vec3 gridSmooth(float dividers) {
  vec3 col = vec3(smoothstep(0.0, 1.0/50.0, abs(abs((fract(texcoord.y * dividers) - 0.5) * 0.5) - 0.25))
                * smoothstep(0.0, 1.0/50.0, abs(abs((fract(texcoord.x * dividers) - 0.5) * 0.5) - 0.25)));
  return (vec3(1.0)-col)*0.2;
}

void main(void) {
  if (any(greaterThan(texcoord, vec2(1.0))))
    discard;
  float r = texture(tex, texcoord).r;
  color = vec4(r) + (vec4(gridSmooth(128.0), 1.0) + vec4(gridSmooth(256.0), 1.0));
}
