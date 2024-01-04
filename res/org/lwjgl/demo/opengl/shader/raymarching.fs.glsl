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
uniform mat4 mvp;

in vec3 o;
in vec3 d;

vec3 rayMarch(vec3 o, vec3 d, vec3 ts) {
  vec3 p = floor(o), di = vec3(1.0) / d, s = sign(d), t = abs((p + max(s, vec3(0.0)) - o) * di);
  int N = int(ts.x + ts.y + ts.z); // <- maximum can only be manhattan distance
  vec3 c;
  int i;
  for (i = 0; i < N; i++) {
    if (texture(tex, (p+vec3(0.5))/ts).r > 0.0) {
      vec4 cp = mvp * vec4((o+d*t)/ts-vec3(0.5), 1.0);
      gl_FragDepth = cp.z / cp.w;
      return abs(c);
    }
    c = step(t.xyz, t.yzx)*step(t.xyz, t.zxy);
    t += di * s * c;
    p += s * c;
    if (any(lessThan(p, vec3(0.0))) || any(greaterThanEqual(p, ts))) {
      break;
    }
  }
  discard;
}

void main(void) {
  vec3 ts = vec3(textureSize(tex, 0));
  vec3 di = 1.0/d, t = min((vec3(-0.5)-o)*di, (vec3(0.5)-o)*di);
  vec3 p = o + d * max(max(max(t.x, t.y), t.z), 0.0);
  fragColor = vec4(rayMarch((p+vec3(0.5))*ts, d*ts, ts), 1.0);
}
