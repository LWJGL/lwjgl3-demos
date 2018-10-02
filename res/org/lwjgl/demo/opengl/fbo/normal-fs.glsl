#version 110

varying vec3 viewNormal;

void main(void) {
  gl_FragColor = vec4(normalize(viewNormal), 1.0);
}
