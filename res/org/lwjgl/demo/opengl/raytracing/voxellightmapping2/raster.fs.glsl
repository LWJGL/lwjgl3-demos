/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform sampler2D lightmap;
uniform samplerBuffer materials;
uniform usamplerBuffer faces;
uniform ivec2 lightmapSize;

centroid in vec2 lightmapCoords_varying;
flat in int matIndex;
in vec3 dir_varying;
in vec3 normal_varying;
centroid in vec3 pos;

layout(location=0) out vec4 color;

struct hitinfo {
  float t;
  uint i;
  uint descends;
  uint ropes;
  uint nodeIdx;
  vec2 uv;
};
bool intersectScene(uint nodeIdx, vec3 origin, vec3 dir, out hitinfo shinfo);

#define WINDOW_MATERIAL_INDEX 79
#define WINDOW_ATTENUATION 1.0
#define SKY_COLOR vec3(0.42, 0.53, 0.69)

vec2 texCoordsFromFace(uvec4 f) {return vec2(f.w & 0xFFFFu, f.w >> 16u);}
uint matIndexFromFace(uvec4 f) {return f.x >> 24u;}

void window() {
  hitinfo hinfo;
  vec3 dir = normalize(dir_varying);
  if (intersectScene(0u, pos + normal_varying*1E-4, reflect(dir, normal_varying), hinfo)) {
    uvec4 f = texelFetch(faces, int(hinfo.i));
    uint mat = matIndexFromFace(f);
    vec2 txc = texCoordsFromFace(f), uv = txc + hinfo.uv + vec2(0.5);
    vec3 lm = vec4(texture(lightmap, uv / lightmapSize)).rgb;
    vec3 col = texelFetch(materials, int(mat)).rgb;
    color = vec4(col * lm * WINDOW_ATTENUATION, 1.0);
  } else {
    color = vec4(SKY_COLOR, 1.0);
  }
}

void other() {
  vec3 col = texelFetch(materials, matIndex).rgb;
  vec3 lm = vec4(texture(lightmap, lightmapCoords_varying)).rgb;
  color = vec4(col * lm, 1.0);
}

void main(void) {
  if (matIndex == WINDOW_MATERIAL_INDEX) {
    window();
  } else {
    other();
  }
}
