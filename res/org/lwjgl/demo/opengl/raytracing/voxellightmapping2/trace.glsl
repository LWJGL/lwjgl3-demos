/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 330 core

uniform usamplerBuffer nodes;
uniform usamplerBuffer leafnodes;
uniform usamplerBuffer faces;

#define MAX_DESCEND 200u
#define MAX_ROPES 100u
#define SIDE_Y_NEG 2u
#define SIDE_Z_NEG 4u
#define AXIS_SHIFT 30u
#define SHORT_BITS 16u
#define AXIS_MASK 3u
#define SPLITPOS_MASK 0x3FFFu
#define SHORT_MASK 0xFFFFu
#define BYTE_MASK 0xFFu
#define NO_AXIS AXIS_MASK
#define NO_NODE SHORT_MASK
#define BYTE_SHIFT 8u
#define SHORT_SHIFT 16u

uvec3 unpack8(uint v) {
  return uvec3(v & BYTE_MASK, v >> BYTE_SHIFT & BYTE_MASK, v >> SHORT_SHIFT & BYTE_MASK);
}
uvec4 unpack8_4(uint v) {
  return uvec4(v & BYTE_MASK, v >> BYTE_SHIFT & BYTE_MASK, v >> SHORT_SHIFT & BYTE_MASK, v >> 24u);
}

uint axis(uvec3 n) {return n.z >> AXIS_SHIFT & AXIS_MASK;}
uint splitPos(uvec3 n) {return n.z >> SHORT_BITS & SPLITPOS_MASK;}
uint nodeIndex(uint n) {return n & SHORT_MASK;}
uint rope(uvec4 n, uint r) {return n[1u + (r >> 1u)] >> (r & 1u) * SHORT_BITS & SHORT_MASK;}
uint firstFace(uvec4 ln) {return ln.x & SHORT_MASK;}
uint numFaces(uvec4 ln) {return ln.x >> SHORT_BITS & SHORT_MASK;}
uvec3 boxMin(uvec3 ng) {return unpack8(ng.x);}
uvec3 boxMax(uvec3 ng) {return unpack8(ng.y);}
uint leftChild(uint nodeIdx) {return nodeIdx + 1u;}
uint rightChild(uvec3 n) {return nodeIndex(n.z);}

bool inrect(vec3 p, vec3 c, vec4 x, vec4 y, out vec2 uv) {
  uv = vec2(dot(p-c, x.xyz) / x.w, dot(p-c, y.xyz) / y.w);
  return all(greaterThanEqual(uv, vec2(0.0)))
      && all(lessThan(uv, vec2(x.w, y.w)));
}

struct rectangle {
  vec3 c;
  vec3 x;
  vec3 y;
};
bool intersectRectangle(vec3 origin, vec3 dir, vec3 rc, vec4 rx, vec4 ry, out float t, out vec2 uv) {
  vec3 n = cross(rx.xyz, ry.xyz);
  float den = dot(n, dir);
  t = dot(rc - origin, n) / den;
  return t > 0.0 && inrect(origin + t * dir, rc, rx, ry, uv);
}

float intersectBox(vec3 origin, vec3 invdir, uvec3 bmin, uvec3 bmax) {
  vec3 lt = vec3(lessThan(invdir, vec3(0.0)));
  vec3 m1 = (vec3(bmin) - origin) * invdir, m2 = ((vec3(bmax) + vec3(1.0)) - origin) * invdir;
  vec3 tmin = mix(m1, m2, lt);
  return max(max(tmin.x, tmin.y), tmin.z);
}

struct hitinfo {
  float t;
  uint i;
  uint descends;
  uint ropes;
  uint nodeIdx;
  vec2 uv;
};

bool intersectFaces(vec3 origin, vec3 dir, uint firstFace, uint numFaces, inout hitinfo hinfo) {
  bool hit = false;
  for (uint i = 0u; i < numFaces; i++) {
    uvec3 f = texelFetch(faces, int(i + firstFace)).xyz;
    vec3 p0 = unpack8(f.x);
    vec4 px = unpack8_4(f.y), py = unpack8_4(f.z);
    float tn;
    vec2 uv;
    if (intersectRectangle(origin, dir, p0, px, py, tn, uv) && tn <= hinfo.t) {
      hinfo.t = tn;
      hinfo.i = i + firstFace;
      hinfo.uv = uv;
      hit = true;
    }
  }
  return hit;
}
float exitSide(vec3 origin, vec3 invdir, uvec3 boxMin, uvec3 boxMax, out uint exitSide) {
  vec3 lt = vec3(lessThanEqual(invdir, vec3(0.0)));
  vec3 tmax = (mix(vec3(boxMax) + vec3(1.0), vec3(boxMin), lt) - origin) * invdir;
  uvec3 signs = uvec3(lt);
  vec2 vals = tmax.y <= tmax.x ? vec2(tmax.y, SIDE_Y_NEG + signs.y) : vec2(tmax.x, signs.x);
  vals = tmax.z <= vals.x ? vec2(tmax.z, SIDE_Z_NEG + signs.z) : vals;
  exitSide = uint(vals.y);
  return vals.x;
}
bool intersectScene(uint nodeIdx, vec3 origin, vec3 dir, out hitinfo shinfo) {
  vec3 invdir = 1.0/dir;
  uvec3 ngn = texelFetch(nodes, int(nodeIdx)).xyz;
  uvec3 nmin = boxMin(ngn), nmax = boxMax(ngn);
  float tEntry = max(0.0, intersectBox(origin, invdir, nmin, nmax));
  shinfo.descends = 0u;
  shinfo.ropes = 0u;
  shinfo.t = 1E30;
  while (true) {
    vec3 pEntry = dir * tEntry + origin;
    while (axis(ngn) != NO_AXIS) {
      uint sp = splitPos(ngn);
      if (sp <= pEntry[axis(ngn)]) {
        nodeIdx = rightChild(ngn);
        nmin[axis(ngn)] = sp;
      } else {
        nodeIdx = leftChild(nodeIdx);
        nmax[axis(ngn)] = sp - 1u;
      }
      ngn = texelFetch(nodes, int(nodeIdx)).xyz;
      if (shinfo.descends++ > MAX_DESCEND)
        return false;
    }
    uint leafIndex = nodeIndex(ngn.z);
    uvec4 ln = texelFetch(leafnodes, int(leafIndex));
    if (intersectFaces(origin, dir, firstFace(ln), numFaces(ln), shinfo)) {
      shinfo.nodeIdx = nodeIdx;
      return true;
    }
    uint exit;
    tEntry = exitSide(origin, invdir, nmin, nmax, exit);
    nodeIdx = rope(ln, exit);
    if (nodeIdx == NO_NODE)
      return false;
    ngn = texelFetch(nodes, int(nodeIdx)).xyz;
    nmin = boxMin(ngn);
    nmax = boxMax(ngn);
    if (shinfo.ropes++ > MAX_ROPES)
      return false;
  }
  return false;
}
