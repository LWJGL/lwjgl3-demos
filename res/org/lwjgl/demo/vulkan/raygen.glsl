/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_NV_ray_tracing : require

layout(binding = 0, set = 0) uniform accelerationStructureNV topLevelAS;
layout(binding = 1, set = 0, rgba8) uniform image2D image;
layout(binding = 2, set = 0) uniform CameraProperties
{
	mat4 projInverse;
	mat4 viewInverse;
} cam;
layout(location = 0) rayPayloadNV Payload {
	vec3 normal;
	float t;
} payload;

#define BOUNCES 3
#define SPP 1
#define BOX_COLOR vec3(196.0, 152.0, 116.0)/255.0

#include "random.inc.glsl"

ivec2 px;

vec2 randWhiteNoise(int i) {
  return vec2(
    random3(vec3(vec2(-px), 1.412 + i)),
    random3(vec3(vec2(px), i)));
}

float sky(vec3 d) {
  return pow(min(1.0, max(0.0, d.y + 1.0)), 4.0);
}

void main() {
    px = ivec2(gl_LaunchIDNV.xy);
	vec2 pc = vec2(px) + vec2(0.5);
	vec2 nc = pc / vec2(gl_LaunchSizeNV.xy) * 2.0 - vec2(1.0);
	vec3 origin = cam.viewInverse[3].xyz;
	vec4 z = cam.projInverse * vec4(nc, 0.0, 1.0);
	vec3 direction = (cam.viewInverse * vec4(normalize(z.xyz), 0.0)).xyz;
	vec3 col = vec3(0.0);
	for (int s = 0; s < SPP; s++) {
	    vec3 o = origin;
	    vec3 d = direction;
		vec3 att = vec3(1.0);
	 	for (int b = 0; b < BOUNCES; b++) {
			payload.t = 0.0;
			traceNV(topLevelAS, gl_RayFlagsOpaqueNV | gl_RayFlagsCullBackFacingTrianglesNV, 0x1, 0, 0, 0, o, 1E-3, d, 1E3, 0);
			if (payload.t > 0.0) {
				o = o + d * payload.t * 0.99;
				vec2 rnd = randWhiteNoise(s * SPP + b);
				vec4 s = randomCosineWeightedHemisphereDirection(payload.normal, rnd);
				d = s.xyz;
			    att *= BOX_COLOR * ONE_OVER_PI * dot(d, payload.normal);
			    if (s.w > 1E-4)
			      att /= s.w;
			} else {
				col += att * sky(d);
				break;
			}
		}
	}
	imageStore(image, px, vec4(col / SPP, 1.0));
}
