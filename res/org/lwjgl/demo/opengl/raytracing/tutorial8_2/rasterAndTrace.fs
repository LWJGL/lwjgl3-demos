/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 430 core

uniform float time;
in vec4 worldPosition;
in vec4 worldNormal;
in vec3 dir;
in vec4 clipPosition;
in vec4 prevClipPosition;

out vec4 color_out;
out vec2 velocity_out;

#define PI 3.14159265359
#define TWO_PI 6.28318530718
#define ONE_OVER_PI (1.0 / PI)
#define ONE_OVER_2PI (1.0 / TWO_PI)
#define LARGE_FLOAT 1.0/0.0
#define EPSILON 1E-4
#define LIGHT_INTENSITY 1.0
#define SKY_COLOR vec3(0.92, 0.97, 1.00)
#define PHONG_EXPONENT 8000.0
#define BOX_ATTENUATION 0.8
#define BOUNCES 2
#define MAX_NUM_BOXES 64

float random3(vec3 f);
vec4 randomCosineWeightedHemisphereDirection(vec3 n, vec2 rand);
vec4 randomPhongWeightedHemisphereDirection(vec3 r, float a, vec2 rand);

struct box {
  vec3 min;
  vec3 max;
};

layout (std140) uniform Boxes {
  box[MAX_NUM_BOXES] boxes;
};
uniform int numBoxes;

struct hitinfo {
  vec3 normal;
  float near;
  int bi;
};
ivec2 px;

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

bool intersectBoxes(vec3 origin, vec3 dir, out hitinfo info) {
  float smallest = LARGE_FLOAT;
  bool found = false;
  vec3 normal;
  for (int i = 0; i < numBoxes; i++) {
    box b = boxes[i];
    vec2 lambda = intersectBox(origin, dir, b, normal);
    if (lambda.y >= 0.0 && lambda.x < lambda.y && lambda.x < smallest) {
      info.normal = normal;
      info.near = lambda.x;
      info.bi = i;
      smallest = lambda.x;
      found = true;
    }
  }
  return found;
}

vec3 randWhiteNoise(int s) {
  return vec3(
    random3(vec3(px + ivec2(s), time)),
    random3(vec3(px + ivec2(s), 1.1+time*1.1)),
    random3(vec3(px + ivec2(s), 0.3+time*0.3)));
}

float brdfSpecular(vec3 i, vec3 o, vec3 n) {
  float a = PHONG_EXPONENT;
  vec3 r = reflect(-i, n);
  return pow(max(0.0, dot(r, o)), a) * (a + 2.0) * ONE_OVER_2PI;
}
float brdfDiffuse(vec3 i, vec3 o, vec3 n) {
  return BOX_ATTENUATION * ONE_OVER_PI;
}

float fresnel(vec3 h, vec3 n) {
  return pow(1.0 - max(0.0, dot(n, h)), 5.0);
}

vec3 trace(vec3 origin, vec3 dir, vec3 normal) {
  float att = 1.0;
  for (int bounce = 0; bounce < BOUNCES; bounce++) {
    vec3 rand = randWhiteNoise(bounce);
    vec4 s;
    if (rand.z < fresnel(-dir, normal)) {
      s = randomPhongWeightedHemisphereDirection(reflect(dir, normal), PHONG_EXPONENT, rand.xy);
      att *= brdfSpecular(s.xyz, -dir, normal);
    } else {
      s = randomCosineWeightedHemisphereDirection(normal, rand.xy);
      att *= brdfDiffuse(s.xyz, -dir, normal) * max(0.0, dot(s.xyz, normal));
    }
    dir = s.xyz;
    if (s.w > 0.0)
      att /= s.w;
    hitinfo hinfo;
    if (!intersectBoxes(origin, dir, hinfo)) {
      return LIGHT_INTENSITY * SKY_COLOR * att;
    }
    box b = boxes[hinfo.bi];
    vec3 point = origin + hinfo.near * dir;
    normal = hinfo.normal;
    origin = point + normal * EPSILON;
  }
  return vec3(0.0);
}

void main(void) {
  px = ivec2(gl_FragCoord.xy);
  vec2 texCoord = clipPosition.xy/clipPosition.w * 0.5;
  vec2 prevTexCoord = prevClipPosition.xy/prevClipPosition.w * 0.5;
  vec3 origin = worldPosition.xyz + worldNormal.xyz * 1E-4;
  vec3 color = trace(origin, normalize(dir), worldNormal.xyz);
  color_out = vec4(color, 1.0);
  velocity_out = texCoord - prevTexCoord;
}
