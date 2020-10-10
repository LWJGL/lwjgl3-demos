/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform sampler2D lightmap;
uniform samplerBuffer materials;
uniform usamplerBuffer faces;

centroid in vec2 lightmapCoords_varying;
flat in int matIndex;
in vec3 dir_varying;
in vec3 normal_varying;
in vec3 pos;

layout(location=0) out vec4 color;

struct hitinfo {
  float t;
  uint i;
  uint descends;
  uint ropes;
  uint nodeIdx;
};
bool intersectScene(uint nodeIdx, vec3 origin, vec3 dir, out hitinfo shinfo);

void main(void) {
  vec3 col = texelFetch(materials, matIndex).rgb;
  vec3 lm = vec4(texture(lightmap, lightmapCoords_varying)).rgb;
  color = vec4(col * lm, 1.0);
}
