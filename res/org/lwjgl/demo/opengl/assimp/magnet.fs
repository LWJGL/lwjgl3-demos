/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 110

uniform vec3 uLightPosition;
uniform vec3 uViewPosition;
uniform vec3 uAmbientColor;
uniform vec3 uDiffuseColor;
uniform vec3 uSpecularColor;
varying vec3 vPosition;
varying vec3 vNormal;

void main() {
    float ambientStrength = 0.5;
    float diffuseStrength = 0.5;
    float specularStrength = 0.5;
    float shininess = 4.0;
    vec3 ambientColor = ambientStrength * uAmbientColor;
    vec3 normal = normalize(vNormal);
    vec3 lightDirection = normalize(uLightPosition - vPosition);
    vec3 diffuseColor = diffuseStrength * max(0.0, dot(normal, lightDirection)) * uDiffuseColor;
    vec3 viewDirection = normalize(uViewPosition - vPosition);
    vec3 reflectDirection = reflect(-lightDirection, normal);
    vec3 specularColor = specularStrength
            * pow(max(dot(viewDirection, reflectDirection), 0.0), shininess) * uSpecularColor;
    gl_FragColor = vec4(ambientColor + diffuseColor + specularColor, 1.0);
}
