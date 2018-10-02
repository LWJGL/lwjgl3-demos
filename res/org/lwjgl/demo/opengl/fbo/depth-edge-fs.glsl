/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform sampler2D normalTex;
uniform sampler2D depthTex;
uniform mat4 inverseMatrix;
uniform float invWidth;
uniform float invHeight;
uniform int showEdge;

varying vec2 coord;

vec3 viewPos(vec2 texcoord) {
  float depth = texture2D(depthTex, texcoord).r;
  vec2 vertexNDC = texcoord * 2.0 - vec2(1.0, 1.0);
  vec4 ndc = vec4(vertexNDC, depth * 2.0 - 1.0, 1.0);
  vec4 view = inverseMatrix * ndc;
  view.xyz /= view.w;
  return view.xyz;
}

vec3 viewNormal(vec2 texcoord) {
  return texture2D(normalTex, texcoord).xyz;
}

float edge() {
  float scale = 2.0;
  vec3 pcZ = viewPos(coord);
  vec3 pcN = viewNormal(coord);
  vec3 pxZ = viewPos(coord + vec2(invWidth*scale, 0.0));
  vec3 pxN = viewNormal(coord + vec2(invWidth*scale, 0.0));
  vec3 pyZ = viewPos(coord + vec2(0.0, invHeight*scale));
  vec3 pyN = viewNormal(coord + vec2(0.0, invHeight*scale));
  // reconstruct the expected normal from the dX/dY view-space positions
  vec3 recN = normalize(cross(pcZ - pxZ, pcZ - pyZ));
  // and compare it with the actual view-space normal
  float d = length(recN - pcN);
  return 1.0 - d*d;
}

vec3 rgb2hsv(vec3 c);
vec3 hsv2rgb(vec3 c);

void main(void) {
  vec4 col = texture2D(normalTex, coord);
  vec3 hsv = rgb2hsv(col.rgb);
  hsv.g *= 0.4;
  vec4 c = vec4(hsv2rgb(hsv), 1.0);
  vec4 final = vec4(1.0);
  if (col.a > 0.0)
    final *= c;
  if (showEdge == 1) {
	final *= vec4(edge());
  }
  gl_FragColor = final;
}
