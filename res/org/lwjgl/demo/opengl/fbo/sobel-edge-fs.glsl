#version 110

uniform sampler2D normalTex;
uniform float invWidth;
uniform float invHeight;
uniform float threshold;

varying vec2 coord;

vec4 edge() {
  vec2 ox = vec2(0.0, 0.0);
  ox.x = invWidth;
  vec2 oy = vec2(0.0, 0.0);
  oy.y = invHeight;

  vec4 g00, g01, g02;
  vec4 g10, g12;
  vec4 g20, g21, g22;

  vec2 PP = coord - oy;
  g00 = texture2D(normalTex, vec2(PP-ox));
  g01 = texture2D(normalTex, vec2(PP));
  g02 = texture2D(normalTex, vec2(PP+ox));

  PP = coord;
  g10 = texture2D(normalTex, vec2(PP-ox));
  g12 = texture2D(normalTex, vec2(PP+ox));

  PP = coord + oy;
  g20 = texture2D(normalTex, vec2(PP-ox));
  g21 = texture2D(normalTex, vec2(PP));
  g22 = texture2D(normalTex, vec2(PP+ox));

  vec4 sx = vec4(0.0), sy = vec4(0.0);
  sx = sx - g00 - g01 * 2.0 - g02;
  sx = sx + g20 + g21 * 2.0 + g22;
  sy = sy - g00 - g10 * 2.0 - g20;
  sy = sy + g02 + g12 * 2.0 + g22;

  float dist = length(sx) + length(sy);
  return vec4(1.0 - dist);
}

void main(void) {
  vec4 col = texture2D(normalTex, coord);
  gl_FragColor = edge() * col;
}
