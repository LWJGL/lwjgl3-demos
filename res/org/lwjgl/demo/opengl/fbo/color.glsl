/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

/**
 * http://lolengine.net/blog/2013/07/27/rgb-to-hsv-in-glsl
 */
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1E-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

/**
 * http://lolengine.net/blog/2013/07/27/rgb-to-hsv-in-glsl
 */
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(vec3(c.x, c.x, c.x) + K.xyz) * 6.0 - vec3(K.w, K.w, K.w));
    return c.z * mix(vec3(K.x, K.x, K.x), clamp(p - vec3(K.x, K.x, K.x), 0.0, 1.0), c.y);
}
