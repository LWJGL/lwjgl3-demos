/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform samplerBuffer materials;

in FS_IN {
  vec4 ao;
  vec2 surfacePos;
  flat int matIndex;
} fs_in;
centroid in vec2 uv;

layout(location=0) out vec4 color;

const float gridThickness = 0.05;

/*
 * Adapted from: https://www.shadertoy.com/view/wl3Sz2
 */
float filterWidth2(vec2 uv) {
  vec2 dx = dFdx(uv), dy = dFdy(uv);
  return max(length(dx), length(dy));
}
float gridSmooth() {
  vec2 q = fs_in.surfacePos + vec2(0.5);
  q -= floor(q);
  q = (gridThickness + 1.0) * 0.5 - abs(q - 0.5);
  float w = 5.0 * filterWidth2(fs_in.surfacePos);
  return 1.0 - smoothstep(0.5 - w * sqrt(gridThickness), 0.5 + w, max(q.x, q.y));
}

void main(void) {
  vec3 col = texelFetch(materials, fs_in.matIndex).rgb;
  vec2 cuv = clamp(uv, vec2(0.0), vec2(1.0));
  float aom = mix(mix(fs_in.ao.x, fs_in.ao.z, cuv.y), mix(fs_in.ao.y, fs_in.ao.w, cuv.y), cuv.x);
  float g = gridSmooth();
  color = aom * g * vec4(col, 0.5);
}
