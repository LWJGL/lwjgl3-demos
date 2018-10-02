/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

layout(binding = 0, rgba8) writeonly uniform image2D framebufferImage;

uniform vec3 ray00;
uniform vec3 ray01;
uniform vec3 ray10;
uniform vec3 ray11;

struct triangle {
  vec3 p0;
  vec3 n0;
  vec3 p1;
  vec3 n1;
  vec3 p2;
  vec3 n2;
};
layout(std430, binding=1) readonly buffer Triangles {
  triangle[] triangles;
};

#define MAX_SCENE_BOUNDS 100.0

struct hitinfo {
  float u;
  float v;
  float t;
  vec3 n;
};

float intersectTriangle(vec3 ray, vec3 v0, vec3 v1, vec3 v2, out hitinfo i) {
  vec3 edge1 = v1 - v0;
  vec3 edge2 = v2 - v0;
  vec3 pvec = cross(ray, edge2);
  float det = dot(edge1, pvec);
  if (det <= 0)
    return -1.0;
  vec3 tvec = -v0;
  i.u = dot(tvec, pvec);
  if (i.u < 0.0 || i.u > det)
    return -1.0;
  vec3 qvec = cross(tvec, edge1);
  i.v = dot(ray, qvec);
  if (i.v < 0.0 || i.u + i.v > det)
    return -1.0;
  float invDet = 1.0 / det;
  float t = dot(edge2, qvec) * invDet;
  return t;
}

bool intersectTriangles(vec3 dir, inout hitinfo info) {
  bool found = false;
  hitinfo tinfo;
  uint numTris = triangles.length();
  for (int i = 0; i < numTris; i++) {
    const triangle tri = triangles[i];
    float t = intersectTriangle(dir, tri.p0, tri.p1, tri.p2, tinfo);
    if (t >= 0.0 && t < info.t) {
      info.t = t;
      // Interpolate using barycentric coordinates
      info.n = normalize((1.0 - tinfo.u - tinfo.v) * tri.n0 + tinfo.u * tri.n1 + tinfo.v * tri.n2);
      found = true;
    }
  }
  return found;
}

vec4 trace(vec3 dir) {
  hitinfo i;
  i.t = MAX_SCENE_BOUNDS;
  if (intersectTriangles(dir, i)) {
    return vec4(i.n, 1.0);
  }
  return vec4(0.0);
}

layout (local_size_x = 16, local_size_y = 8) in;

void main(void) {
  ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size = imageSize(framebufferImage);
  if (pix.x >= size.x || pix.y >= size.y) {
    return;
  }
  vec2 pos = (vec2(pix) + vec2(0.5, 0.5)) / vec2(size.x, size.y);
  vec3 dir = mix(mix(ray00, ray01, pos.y), mix(ray10, ray11, pos.y), pos.x);
  dir = normalize(dir);
  vec4 color = trace(dir);
  imageStore(framebufferImage, pix, color);
}
