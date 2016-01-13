#version 110

uniform samplerCube tex;
uniform vec3 cameraPosition;

varying vec3 dir;

// Could probably be uniforms
#define blackholePosition vec3(0.0, 0.0, 0.0)
#define blackholeSize 2.0
#define blackholeStrength 2.0
#define blackholeHorizonSharpness 6.0

vec4 distortion(void) {
  vec3 ndir = normalize(dir);
  // Compute the shortest distance between the ray(cam, dir) and the position of the black hole.
  // This will determine how much we will bend the light.
  float distance = length(cross(ndir, cameraPosition - blackholePosition));

  // Compute a mix/blend factor for how much the light will be visible.
  // It will not be visible when it is too close to the black hole.
  // We do this using a simple smoothstep falloff, which we then harden with pow().
  float val = smoothstep(0.0, blackholeSize, distance);
  val = pow(val, blackholeHorizonSharpness); // <- harder edge than smoothstep

  // Compute direction of shortest vector between ray(cam, dir) and blackhole.
  // This will be our distortion vector.
  // This vector always point from the blackhole to the direction to the 'dir' ray.
  vec3 perp = normalize(cameraPosition + dot(blackholePosition - cameraPosition, ndir) * ndir - blackholePosition);
  // Since we want the distortion to increase towards the black hole center, we need to 
  // multiply with 'val', but invert val's interval because it is bigger when the distance
  // increases.
  perp *= 1.0 - val;

  // Distort our direction vector using that perpendicular vector.
  ndir -= blackholeStrength * perp;
  return vec4(ndir, val);
}

void main(void) {
  vec4 dist = distortion();
  gl_FragColor = textureCube(tex, dist.xyz) * dist.w;
}
