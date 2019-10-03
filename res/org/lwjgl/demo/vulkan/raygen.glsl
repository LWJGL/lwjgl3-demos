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

#define BOUNCES 2

void main() {
	vec2 pc = vec2(gl_LaunchIDNV.xy) + vec2(0.5);
	vec2 nc = pc / vec2(gl_LaunchSizeNV.xy) * vec2(2.0) - vec2(1.0);
	vec3 origin = cam.viewInverse[3].xyz;
	vec4 d = cam.projInverse * vec4(nc, 0.0, 1.0);
	vec4 direction = cam.viewInverse * vec4(normalize(d.xyz), 0.0);
	float att = 1.0;
	for (int i = 0; i < BOUNCES; i++) {
		payload.t = 0.0;
		traceNV(topLevelAS, gl_RayFlagsOpaqueNV, 0x1, 0, 0, 0, origin, 1E-3, direction.xyz, 1E2, 0);
		if (payload.t > 0.0) {
			origin = origin + direction.xyz * payload.t;
			direction.xyz = normalize(reflect(direction.xyz, payload.normal));
			att *= 0.8;
		} else {
			break;
		}
	}
	vec3 color = abs(direction.xyz);//texture(tex, direction.xyz).rgb;
	imageStore(image, ivec2(gl_LaunchIDNV.xy), vec4(color * att, 1.0));
}
