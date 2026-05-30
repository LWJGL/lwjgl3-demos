/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core
#define NUM_CASCADES 4

uniform sampler2DArrayShadow shadowMap;
uniform mat4 lightViewProjection[NUM_CASCADES];
uniform float cascadeSplits[NUM_CASCADES];
uniform vec3 lightDir;
uniform bool visualizeCascades;

in vec3 vWorldPos;
in vec3 vNormal;
in float vViewZ;

out vec4 color;

const vec3 cascadeColors[NUM_CASCADES] = vec3[NUM_CASCADES](
    vec3(1.0, 0.4, 0.4), vec3(0.4, 1.0, 0.4), vec3(0.4, 0.6, 1.0), vec3(1.0, 1.0, 0.4));

/* 3x3 PCF on the selected cascade layer. */
float sampleShadow(int layer, vec3 proj) {
    if (proj.x < 0.0 || proj.x > 1.0 || proj.y < 0.0 || proj.y > 1.0 || proj.z > 1.0)
        return 1.0;
    float bias = 0.0015;
    vec2 texel = 1.0 / vec2(textureSize(shadowMap, 0).xy);
    float sum = 0.0;
    for (int x = -1; x <= 1; ++x)
        for (int y = -1; y <= 1; ++y)
            sum += texture(shadowMap, vec4(proj.xy + vec2(x, y) * texel, float(layer), proj.z - bias));
    return sum / 9.0;
}

void main(void) {
    int layer = NUM_CASCADES - 1;
    for (int i = 0; i < NUM_CASCADES; ++i) {
        if (vViewZ < cascadeSplits[i]) { layer = i; break; }
    }
    vec4 lc = lightViewProjection[layer] * vec4(vWorldPos, 1.0);
    vec3 proj = lc.xyz / lc.w;
    float shadow = sampleShadow(layer, proj);
    vec3 N = normalize(vNormal);
    float ndotl = max(dot(N, lightDir), 0.0);
    float ambient = 0.25;
    vec3 base = vec3(0.85);
    if (visualizeCascades)
        base *= cascadeColors[layer];
    color = vec4(base * (ambient + (1.0 - ambient) * ndotl * shadow), 1.0);
}
