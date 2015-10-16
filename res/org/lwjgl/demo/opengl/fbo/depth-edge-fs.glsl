#version 110

uniform sampler2D normalTex;
uniform sampler2D depthTex;
uniform mat4 inverseMatrix;
uniform float invWidth;
uniform float invHeight;

varying vec2 coord;

float viewZ(vec2 texcoord) {
  float depth = texture2D(depthTex, texcoord).r;
  vec2 vertexNDC = texcoord * 2.0 - vec2(1.0, 1.0);
  vec4 ndc = vec4(vertexNDC, depth * 2.0 - 1.0, 1.0);
  vec4 view = inverseMatrix * ndc;
  view /= view.w;
  return view.z;
}

vec4 edge() {
  vec2 ox = vec2(0.0, 0.0);
  ox.x = invWidth;
  vec2 oy = vec2(0.0, 0.0);
  oy.y = invHeight;

  vec2 PP = coord - oy;
  float g00 = viewZ(PP-ox);
  float g01 = viewZ(PP);
  float g02 = viewZ(PP+ox);

  PP = coord;
  float g10 = viewZ(PP-ox);
  float g12 = viewZ(PP+ox);

  PP = coord + oy;
  float g20 = viewZ(PP-ox);
  float g21 = viewZ(PP);
  float g22 = viewZ(PP+ox);

  float sx = 0.0, sy = 0.0;
  sx = sx - g00 - g01 * 2.0 - g02 + g20 + g21 * 2.0 + g22;
  sy = sy - g00 - g10 * 2.0 - g20 + g02 + g12 * 2.0 + g22;

  float dist = abs(sx) + abs(sy);
  return vec4(1.0 - dist);
}

vec3 rgb2hsv(vec3 c);
vec3 hsv2rgb(vec3 c);

void main(void) {
  vec4 col = texture2D(normalTex, coord);
  float vd = viewZ(coord);
  if (vd < -10.0) {
    discard;
  }
  vec3 hsv = rgb2hsv(col.rgb);
  hsv.g *= 0.5;
  col = vec4(hsv2rgb(hsv), 1.0);
  gl_FragColor = edge();
}
