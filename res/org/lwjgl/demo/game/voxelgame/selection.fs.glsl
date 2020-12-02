/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

layout(std140) uniform Uniforms {
  mat4 mvp;
};

in vec2 quad_out;

layout(location=0) out vec4 color;

const float gridThickness = 0.1;

/*
 * Adapted from: https://www.shadertoy.com/view/wl3Sz2
 */
float filterWidth2(vec2 uv) {
  vec2 dx = dFdx(uv), dy = dFdy(uv);
  return max(length(dx), length(dy));
}
float gridSmooth() {
  vec2 q = quad_out + vec2(0.5);
  q -= floor(q);
  q = (gridThickness + 1.0) * 0.5 - abs(q - 0.5);
  float w = 5.0 * filterWidth2(quad_out);
  return 1.0 - smoothstep(0.5 - w * sqrt(gridThickness), 0.5 + w, max(q.x, q.y));
}

void main(void) {
  color = (1.0 - gridSmooth()) * vec4(0.0, 0.0, 0.0, 1.0);
}
