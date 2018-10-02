/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform sampler2D normalTex;
uniform float invWidth;
uniform float invHeight;
uniform int showEdge;

varying vec2 coord;

vec4 edge() {
  vec2 ox = vec2(0.0, 0.0);
  ox.x = invWidth;
  vec2 oy = vec2(0.0, 0.0);
  oy.y = invHeight;

  vec2 PP = coord - oy;
  vec4 g00 = texture2D(normalTex, PP-ox);
  vec4 g01 = texture2D(normalTex, PP);
  vec4 g02 = texture2D(normalTex, PP+ox);

  PP = coord;
  vec4 g10 = texture2D(normalTex, PP-ox);
  vec4 g12 = texture2D(normalTex, PP+ox);

  PP = coord + oy;
  vec4 g20 = texture2D(normalTex, PP-ox);
  vec4 g21 = texture2D(normalTex, PP);
  vec4 g22 = texture2D(normalTex, PP+ox);

  vec4 sx = vec4(0.0), sy = vec4(0.0);
  sx = sx - g00 - g01 * 2.0 - g02 + g20 + g21 * 2.0 + g22;
  sy = sy - g00 - g10 * 2.0 - g20 + g02 + g12 * 2.0 + g22;

  float dist = (length(sx) + length(sy)) / 4.0;
  return vec4(1.0 - dist);
}

vec3 rgb2hsv(vec3 c);
vec3 hsv2rgb(vec3 c);

void main(void) {
  vec4 col = texture2D(normalTex, coord);
  vec3 hsv = rgb2hsv(col.rgb);
  hsv.g *= 0.4;
  col = vec4(hsv2rgb(hsv), 1.0);
  vec4 final = col;
  if (showEdge == 1)
    final *= edge();
  gl_FragColor = final;
}
