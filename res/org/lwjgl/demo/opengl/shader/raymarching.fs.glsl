/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

layout (location = 0) out vec4 fragColor;

uniform sampler3D tex;

in vec3 o;
in vec3 d;

vec3 rayMarch2(vec3 o, vec3 d, vec3 ts) {
  o *= ts;
  vec3 p = floor(o), di = vec3(1.0) / d, s = sign(d), t = abs((p + max(s, vec3(0.0)) - o) * di);
  int N = int(max(ts.x, max(ts.y, ts.z)))*3;
  for (int i = 0; i < N; i++) {
    if (texture(tex, (p+vec3(0.5))/ts).r > 0.0) {
      return vec3(p/ts);
    }
    vec3 c = step(t.xyz, t.yzx)*step(t.xyz, t.zxy);
    t += di * s * c;
    p += s * c;
    if (any(lessThan(p, vec3(0.0))) || any(greaterThanEqual(p, ts))) {
      return vec3(i)/vec3(N);
    }
  }
  return vec3(1.0, 0.0, 1.0);
}

void main(void) {
  vec3 ts = vec3(textureSize(tex, 0));
  fragColor = vec4(rayMarch2(o, d, ts), 1.0);
}
