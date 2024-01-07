/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

layout (location = 0) out vec4 fragColor;
#ifdef GL_ARB_conservative_depth
#extension GL_ARB_conservative_depth : enable
layout (depth_less) out float gl_FragDepth;
#endif

uniform sampler3D tex;
uniform vec3 size;
uniform mat4 mvp;

in vec3 o;
in vec3 d;

vec3 rayMarch(vec3 o, vec3 d, vec3 n, vec3 size) {
  vec3 ts = vec3(textureSize(tex, 0));
  float ti = 0.0;
  vec3 p = floor(o), di = vec3(1.0) / d, s = sign(d), t = abs((p + max(s, vec3(0.0)) - o) * di);
  int N = int(size.x + size.y + size.z); // <- maximum can only be manhattan distance
  vec3 c;
  int i;
  for (i = 0; i < N; i++) {
    if (texture(tex, (p+vec3(0.5))/ts).r > 0.0) {
      vec4 cp = mvp * vec4((o+d*ti)/size-vec3(0.5), 1.0);
      gl_FragDepth = cp.z / cp.w;
      return n;
    }
    c = step(t.xyz, t.yzx)*step(t.xyz, t.zxy);
    vec3 ds = s*c;
    ti = min(t.x, min(t.y, t.z));
    n = -ds;
    t += di * ds;
    p += ds;
    if (any(lessThan(p, vec3(0.0))) || any(greaterThanEqual(p, ts))) {
      break;
    }
  }
  discard;
}

void main(void) {
  vec3 ts = vec3(textureSize(tex, 0));
  vec3 di = 1.0/d, t = min((vec3(-0.5)-o)*di, (vec3(0.5)-o)*di);
  vec3 s = sign(d), n = -s*step(t.yzx, t.xyz)*step(t.zxy, t.xyz);
  vec3 p = o + d * max(max(max(t.x, t.y), t.z), 0.0);
  fragColor = vec4(rayMarch((p+vec3(0.5))*size, d*size, n, size), 1.0);
}
