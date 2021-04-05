/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

layout(binding = 0, rgba8) uniform image2D framebufferImage;
uniform vec3 eye, ray00, ray01, ray10, ray11;
uniform float roughness;
layout(binding=0) uniform sampler2D ltc_mat;
layout(binding=1) uniform sampler2D ltc_mag;

#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define ONE_OVER_PI (1.0 / PI)
#define ONE_OVER_2PI (1.0 / TWO_PI)
#define LARGE_FLOAT 1E+10
#define EPSILON 1E-4
#define LIGHT_INTENSITY 6.0

#define LUT_SIZE 16.0
#define LUT_SCALE ((LUT_SIZE - 1.0) / LUT_SIZE)
#define LUT_BIAS (0.5 / LUT_SIZE)

bool inrect(vec3 p, vec3 c, vec3 x, vec3 y);

struct box {
  vec3 min, max, col;
};
#define NUM_BOXES 5
const box boxes[NUM_BOXES] = {
  {vec3(-5.0, -0.1, -5.0), vec3( 5.0, 0.0,  5.0), vec3(0.90, 0.85, 0.78)},// <- floor
  {vec3(-5.1,  0.0, -5.0), vec3(-5.0, 5.0,  5.0), vec3(1.00, 0.82, 0.78)},   // <- left wall
  {vec3( 5.0,  0.0, -5.0), vec3( 5.1, 5.0,  5.0), vec3(1.00, 1.00, 0.90)},   // <- right wall
  {vec3(-5.0,  0.0, -5.1), vec3( 5.0, 5.0, -5.0), vec3(0.63, 0.82, 0.37)},// <- back wall
  {vec3(-5.0,  0.0,  5.0), vec3( 5.0, 5.0,  5.1), vec3(0.96, 0.6, 0.29)},  // <- front wall
};

struct rectangle {
  vec3 c;
  vec3 x;
  vec3 y;
};
#define NUM_RECTANGLES 16
const rectangle rectangles[NUM_RECTANGLES] = {
  {vec3( 0.0, 5.0, 3.0), vec3(0.2, 0.0, 0.0), vec3(0.0, -5.0, 0.0)},
  {vec3(-1.0, 4.0, 3.0), vec3(0.2, 0.0, 0.0), vec3(0.0, -4.0, 0.0)},
  {vec3(-2.0, 3.0, 3.0), vec3(0.2, 0.0, 0.0), vec3(0.0, -3.0, 0.0)},
  {vec3(-3.0, 2.0, 3.0), vec3(0.2, 0.0, 0.0), vec3(0.0, -2.0, 0.0)},
  {vec3(-4.0, 1.0, 3.0), vec3(0.2, 0.0, 0.0), vec3(0.0, -1.0, 0.0)},
  {vec3(+4.0, 1.0, 3.0), vec3(0.2, 0.0, 0.0), vec3(0.0, -1.0, 0.0)},
  {vec3(+3.0, 2.0, 3.0), vec3(0.2, 0.0, 0.0), vec3(0.0, -2.0, 0.0)},
  {vec3(+2.0, 3.0, 3.0), vec3(0.2, 0.0, 0.0), vec3(0.0, -3.0, 0.0)},
  {vec3(+1.0, 4.0, 3.0), vec3(0.2, 0.0, 0.0), vec3(0.0, -4.0, 0.0)},
  {vec3(+4.9, 4.0, 4.0), vec3(0.0, 0.0, -5.0), vec3(0.0, -0.2, 0.0)},
  {vec3(+4.9, 3.0, 3.0), vec3(0.0, 0.0, -5.0), vec3(0.0, -0.2, 0.0)},
  {vec3(+4.9, 2.0, 2.0), vec3(0.0, 0.0, -5.0), vec3(0.0, -0.2, 0.0)},
  {vec3(+4.9, 1.0, 1.0), vec3(0.0, 0.0, -5.0), vec3(0.0, -0.2, 0.0)},
  {vec3(-1.0, 5.0, -4.9), vec3(0.2, 0.0, 0.0), vec3(0.0, -2.0, 0.0)},
  {vec3(+0.0, 3.0, -4.9), vec3(0.2, 0.0, 0.0), vec3(0.0, -2.0, 0.0)},
  {vec3(+1.0, 5.0, -4.9), vec3(0.2, 0.0, 0.0), vec3(0.0, -2.0, 0.0)},
};

struct hitinfo {
  vec3 normal;
  float near;
  int i;
  bool isRectangle;
};

/**
 * Adapted from: https://eheitzresearch.wordpress.com/415-2/
 */
int clipQuadToHorizon(inout vec3 L0, inout vec3 L1, inout vec3 L2, inout vec3 L3, inout vec3 L4) {
  int config = int(L0.z > 0.0)<<0 | int(L1.z > 0.0)<<1
             | int(L2.z > 0.0)<<2 | int(L3.z > 0.0)<<3;
  switch (config) {
  case 1:
    L1 = -L1.z * L0 + L0.z * L1;
    L2 = -L3.z * L0 + L0.z * L3;
    L3 =  L0;
    return 3;
  case 2:
    L0 = -L0.z * L1 + L1.z * L0;
    L2 = -L2.z * L1 + L1.z * L2;
    L3 =  L0;
    return 3;
  case 3:
    L2 = -L2.z * L1 + L1.z * L2;
    L3 = -L3.z * L0 + L0.z * L3;
    L4 =  L0;
    return 4;
  case 4:
    L0 = -L3.z * L2 + L2.z * L3;
    L1 = -L1.z * L2 + L2.z * L1;
    L3 =  L0;
    return 3;
  case 6:
    L0 = -L0.z * L1 + L1.z * L0;
    L3 = -L3.z * L2 + L2.z * L3;
    L4 =  L0;
    return 4;
  case 7:
    L4 = -L3.z * L0 + L0.z * L3;
    L3 = -L3.z * L2 + L2.z * L3;
    return 5;
  case 8:
    L0 = -L0.z * L3 + L3.z * L0;
    L1 = -L2.z * L3 + L3.z * L2;
    L2 =  L3;
    L3 =  L0;
    return 3;
  case 9:
    L1 = -L1.z * L0 + L0.z * L1;
    L2 = -L2.z * L3 + L3.z * L2;
    L4 =  L0;
    return 4;
  case 11:
    L4 = L3;
    L3 = -L2.z * L3 + L3.z * L2;
    L2 = -L2.z * L1 + L1.z * L2;
    return 5;
  case 12:
    L1 = -L1.z * L2 + L2.z * L1;
    L0 = -L0.z * L3 + L3.z * L0;
    L4 =  L0;
    return 4;
  case 13:
    L4 =  L3;
    L3 =  L2;
    L2 = -L1.z * L2 + L2.z * L1;
    L1 = -L1.z * L0 + L0.z * L1;
    return 5;
  case 14:
    L4 = -L0.z * L3 + L3.z * L0;
    L0 = -L0.z * L1 + L1.z * L0;
    return 5;
  case 15:
    L4 = L0;
    return 4;
  default:
    return 0;
  }
}

/**
 * Adapted from: https://eheitzresearch.wordpress.com/757-2/
 */
float integrateEdge(vec3 v1, vec3 v2) {
  float x = dot(v1, v2), y = abs(x);
  float a = 0.8543985 + (0.4965155 + 0.0145206*y)*y;
  float b = 3.4175940 + (4.1616724 + y)*y;
  float v = a / b;
  return (v1.x * v2.y - v1.y * v2.x)
       * mix(v, 0.5 * inversesqrt(max(1.0 - x * x, 1E-7)) - v, float(x <= 0.0));
}

/**
 * Adapted from: https://eheitzresearch.wordpress.com/415-2/
 */
float ltcEvaluateAboveHemisphere(vec3 L0, vec3 L1, vec3 L2, vec3 L3, vec3 L4, int n) {
  L0 = normalize(L0);
  L1 = normalize(L1);
  L2 = normalize(L2);
  L3 = normalize(L3);
  L4 = normalize(L4);
  return abs(integrateEdge(L0, L1)
           + integrateEdge(L1, L2)
           + integrateEdge(L2, L3)
           + mix(integrateEdge(L3, L4), 0.0, float(n < 4))
           + mix(integrateEdge(L4, L0), 0.0, float(n != 5)));
}
float ltcEvaluate(vec3 N, vec3 V, vec3 P, mat3 M, rectangle r) {
  vec3 T1 = normalize(V - N * dot(V, N)), T2 = cross(T1, N);
  mat3 Mt = mat3(T1, T2, N)*M;
  vec3 L0, L1, L2, L3, L4;
  L0 = (r.c - P)*Mt;
  L1 = (r.c + r.x - P)*Mt;
  L2 = (r.c + r.x + r.y - P)*Mt;
  L3 = (r.c + r.y - P)*Mt;
  int n = clipQuadToHorizon(L0, L1, L2, L3, L4);
  if (n == 0)
      return 0.0;
  return ltcEvaluateAboveHemisphere(L0, L1, L2, L3, L4, n);
}

ivec2 px;

bool inrect(vec3 p, vec3 c, vec3 x, vec3 y) {
  vec2 xyp = vec2(dot(p-c, x), dot(p-c, y));
  return all(greaterThanEqual(xyp, vec2(0.0)))
      && all(lessThan(xyp, vec2(dot(x,x), dot(y,y))));
}
bool intersectRectangle(vec3 origin, vec3 dir, rectangle r, out hitinfo hinfo) {
  vec3 n = cross(r.x, r.y);
  float den = dot(n, dir), t = dot(r.c - origin, n) / den;
  hinfo.near = t;
  return den != 0.0 && t > 0.0 && inrect(origin + t * dir, r.c, r.x, r.y);
}

vec2 intersectBox(vec3 origin, vec3 dir, const box b, out vec3 normal) {
  vec3 tMin = (b.min - origin) / dir;
  vec3 tMax = (b.max - origin) / dir;
  vec3 t1 = min(tMin, tMax);
  vec3 t2 = max(tMin, tMax);
  float tNear = max(max(t1.x, t1.y), t1.z);
  float tFar = min(min(t2.x, t2.y), t2.z);
  normal = vec3(equal(t1, vec3(tNear))) * sign(-dir);
  return vec2(tNear, tFar);
}

bool intersectObjects(vec3 origin, vec3 dir, out hitinfo info) {
  float smallest = LARGE_FLOAT;
  bool found = false;
  vec3 normal;
  for (int i = 0; i < NUM_BOXES; i++) {
    vec2 lambda = intersectBox(origin, dir, boxes[i], normal);
    if (lambda.y >= 0.0 && lambda.x < lambda.y && lambda.x < smallest) {
      info.near = lambda.x;
      info.i = i;
      info.isRectangle = false;
      info.normal = normal;
      smallest = lambda.x;
      found = true;
    }
  }
  for (int i = 0; i < NUM_RECTANGLES; i++) {
    hitinfo hinfo;
    if (intersectRectangle(origin, dir, rectangles[i], hinfo) && hinfo.near < smallest) {
      info.near = hinfo.near;
      info.i = i;
      info.isRectangle = true;
      smallest = hinfo.near;
      found = true;
    }
  }
  return found;
}
vec3 normalForRectangle(vec3 hit, const rectangle r) {
  return cross(r.x, r.y);
}

vec3 trace(vec3 origin, vec3 dir) {
  hitinfo hinfo;
  if (!intersectObjects(origin, dir, hinfo))
     return vec3(0.0);
  vec3 point = origin + hinfo.near * dir;
  vec3 normal, albedo;
  if (hinfo.isRectangle) {
    const rectangle r = rectangles[hinfo.i];
    return vec3(1.0);
  } else {
    const box b = boxes[hinfo.i];
    normal = hinfo.normal;
    albedo = b.col;
  }
  origin = point + normal * EPSILON;
  vec2 uv = vec2(roughness, sqrt(1.0 - min(1.0, max(0.0, dot(normal, -dir)))))
              * LUT_SCALE + vec2(LUT_BIAS);
  vec4 m = texture(ltc_mat, uv);
  vec4 f = texture(ltc_mag, uv);
  mat3 M = mat3(
    vec3(m.x, 0.0, m.z),
    vec3(0.0, 1.0, 0.0),
    vec3(m.y, 0.0, m.w)
  );
  vec3 col = vec3(0.0);
  for (int i = 0; i < NUM_RECTANGLES; i++) {
    col += ltcEvaluate(normal, -dir, point, M, rectangles[i]) * f.x * f.y + 
           ltcEvaluate(normal, -dir, point, mat3(1.0), rectangles[i]) * f.x * (1.0 - f.y);
  }
  return col * albedo * LIGHT_INTENSITY;
}

layout (local_size_x = 16, local_size_y = 16) in;

void main(void) {
  px = ivec2(gl_GlobalInvocationID.xy);
  ivec2 size = imageSize(framebufferImage);
  if (any(greaterThanEqual(px, size)))
    return;
  vec2 p = (vec2(px) + vec2(0.5)) / vec2(size);
  vec3 dir = mix(mix(ray00, ray01, p.y), mix(ray10, ray11, p.y), p.x);
  vec3 color = trace(eye, normalize(dir));
  imageStore(framebufferImage, px, vec4(color, 1.0));
}
