/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 460
#extension GL_NV_ray_tracing : require

layout(location = 0) rayPayloadInNV Payload {
    vec3 normal;
    float t;
} payload;

hitAttributeNV vec2 attribs;

layout(binding = 3, set = 0) buffer Normals { uint n[]; } normals;
layout(binding = 4, set = 0) buffer Indices { uint i[]; } indices;

void main() {
    ivec3 index = ivec3(
        indices.i[3 * gl_PrimitiveID],
        indices.i[3 * gl_PrimitiveID + 1],
        indices.i[3 * gl_PrimitiveID + 2]
    );
    vec3 n0 = unpackSnorm4x8(normals.n[index.x]).xyz;
    vec3 n1 = unpackSnorm4x8(normals.n[index.y]).xyz;
    vec3 n2 = unpackSnorm4x8(normals.n[index.z]).xyz;
    vec3 bc = vec3(1.0f - attribs.x - attribs.y, attribs.x, attribs.y);
    payload.t = gl_RayTmaxNV;
    payload.normal = normalize(n0 * bc.x + n1 * bc.y + n2 * bc.z);
}
