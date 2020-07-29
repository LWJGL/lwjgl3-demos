#version 330 core

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

in vec2 texcoord;
out vec4 color_out;

uniform vec3 cam[5];

uniform usamplerBuffer nodes;
uniform usamplerBuffer nodegeoms;
uniform usamplerBuffer leafnodes;
uniform usamplerBuffer voxels;
uniform samplerBuffer materials;

struct hitinfo {
  vec3 normal;
  float t;
  uint i;
  uint descends;
  uint ropes;
  uint nodeIdx;
};
bool intersectVoxel(vec3 origin, vec3 invdir, vec3 bmin, vec3 bmax, out float t, out vec3 normal) {
  float t1 = (bmin.x - origin.x)*invdir.x, t2 = (bmax.x - origin.x)*invdir.x;
  float t3 = (bmin.y - origin.y)*invdir.y, t4 = (bmax.y - origin.y)*invdir.y;
  float t5 = (bmin.z - origin.z)*invdir.z, t6 = (bmax.z - origin.z)*invdir.z;
  float t12m = min(t1, t2), t34m = min(t3, t4), t56m = min(t5, t6);
  t = max(max(t12m, t34m), t56m);
  float tm = min(min(max(t1, t2), max(t3, t4)), max(t5, t6));
  normal = vec3(equal(vec3(t12m, t34m, t56m), vec3(t))) * sign(-invdir);
  return t >= 0.0 && t <= tm;
}
float intersectBox(vec3 origin, vec3 invdir, uvec3 bmin, uvec3 bmax) {
  bvec3 lt = lessThan(invdir, vec3(0.0));
  vec3 m1 = (vec3(bmin) - origin) * invdir, m2 = ((vec3(bmax) + vec3(1.0)) - origin) * invdir;
  vec3 tmin = mix(m1, m2, lt);
  return max(max(tmin.x, tmin.y), tmin.z);
}
uvec3 unpack8(uint v) {
  return uvec3(v & BYTE_MASK, v >> 8u & BYTE_MASK, v >> 16u & BYTE_MASK);
}
bool intersectVoxels(vec3 origin, vec3 invdir, uint firstVoxel, uint numVoxels, inout hitinfo hinfo) {
  bool hit = false;
  for (uint i = 0u; i < numVoxels; i++) {
    uvec2 vpe = texelFetch(voxels, int(i + firstVoxel)).xy;
    vec3 vp = vec3(unpack8(vpe.x)), ve = vec3(unpack8(vpe.y));
    float tn;
    vec3 normal;
    if (intersectVoxel(origin, invdir, vp, vp + ve + vec3(1.0), tn, normal) && tn <= hinfo.t) {
      hinfo.t = tn;
      hinfo.i = i + firstVoxel;
      hinfo.normal = normal;
      hit = true;
    }
  }
  return hit;
}
float exitSide(vec3 origin, vec3 invdir, uvec3 boxMin, uvec3 boxMax, out uint exitSide) {
  bvec3 lt = lessThanEqual(invdir, vec3(0.0));
  vec3 tmax = (mix(vec3(boxMax) + vec3(1.0), vec3(boxMin), lt) - origin) * invdir;
  uvec3 signs = uvec3(lt);
  vec2 vals;
  vals = tmax.y <= tmax.x ? vec2(tmax.y, SIDE_Y_NEG + signs.y) : vec2(tmax.x, signs.x);
  vals = tmax.z <= vals.x ? vec2(tmax.z, SIDE_Z_NEG + signs.z) : vals;
  exitSide = uint(vals.y);
  return vals.x;
}

#define axis(n) ((n) >> AXIS_SHIFT & AXIS_MASK)
#define splitPos(n) ((n) >> SHORT_BITS & SPLITPOS_MASK)
#define nodeIndex(n) ((n) & SHORT_MASK)
#define rope(n, r) ((n)[1u + ((r) >> 1u)] >> ((r) & 1u) * SHORT_BITS & SHORT_MASK)
#define firstVoxel(ln) ((ln).x & SHORT_MASK)
#define numVoxels(ln) ((ln).x >> SHORT_BITS & SHORT_MASK)
#define boxmin(ng) unpack8((ng).x)
#define boxmax(ng) unpack8((ng).y)
#define leftChild(nodeIdx) ((nodeIdx) + 1u)
#define rightChild(n) nodeIndex(n)

bool intersectScene(uint nodeIdx, vec3 origin, vec3 dir, vec3 invdir, out hitinfo shinfo) {
  uint n = texelFetch(nodes, int(nodeIdx)).x;
  uvec2 ng = texelFetch(nodegeoms, int(nodeIdx)).xy;
  uvec3 nmin = boxmin(ng), nmax = boxmax(ng);
  float tEntry = max(0.0, intersectBox(origin, invdir, nmin, nmax));
  shinfo.descends = 0u;
  shinfo.ropes = 0u;
  shinfo.t = 1.0/0.0;
  while (true) {
    vec3 pEntry = dir * tEntry + origin;
    while (axis(n) != NO_AXIS) {
      uint sp = splitPos(n);
      if (sp <= pEntry[axis(n)]) {
        nodeIdx = rightChild(n);
        nmin[axis(n)] = sp;
      } else {
        nodeIdx = leftChild(nodeIdx);
        nmax[axis(n)] = sp - 1u;
      }
      n = texelFetch(nodes, int(nodeIdx)).x;
      if (shinfo.descends++ > MAX_DESCEND)
        return false;
    }
    uint leafIndex = nodeIndex(n);
    uvec4 ln = texelFetch(leafnodes, int(leafIndex));
    if (intersectVoxels(origin, invdir, firstVoxel(ln), numVoxels(ln), shinfo)) {
      shinfo.nodeIdx = nodeIdx;
      return true;
    }
    uint exit;
    tEntry = exitSide(origin, invdir, nmin, nmax, exit);
    nodeIdx = rope(ln, exit);
    if (nodeIdx == NO_NODE)
      return false;
    n = texelFetch(nodes, int(nodeIdx)).x;
    ng = texelFetch(nodegeoms, int(nodeIdx)).xy;
    nmin = boxmin(ng);
    nmax = boxmax(ng);
    if (shinfo.ropes++ > MAX_ROPES)
      return false;
  }
  return false;
}

vec3 trace(vec3 origin, vec3 dir) {
  hitinfo hinfo, hinfo2;
  vec3 col = vec3(0.3, 0.42, 0.62);
  if (intersectScene(0u, origin, dir, 1.0/dir, hinfo)) {
    uint vm = texelFetch(voxels, int(hinfo.i)).x >> 24u;
    col = texelFetch(materials, int(vm)).rgb;
    // Cast shadow ray
    origin += dir * hinfo.t + hinfo.normal * 1E-3;
    dir = vec3(2.0, 1.0, 1.0);
    if (intersectScene(hinfo.nodeIdx, origin, dir, 1.0/dir, hinfo2))
      col *= 0.3;
  }
  //col *= vec3(hinfo.ropes, hinfo.descends, 1.0) * vec3(0.3, 0.05, 1.0);
  return col;
}

void main(void) {
  vec3 dir = mix(mix(cam[1], cam[2], texcoord.y), mix(cam[3], cam[4], texcoord.y), texcoord.x);
  color_out = vec4(trace(cam[0], dir), 1.0);
}
