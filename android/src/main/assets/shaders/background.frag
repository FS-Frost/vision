#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform float uDim;
in vec2 vUv;
out vec4 outColor;
void main() { outColor = vec4(texture(uTex, vUv).rgb * uDim, 1.0); }
