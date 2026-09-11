#version 330

// Shaderpack path, step one of the pocket occluder (see ArmadaPocketOccluder): before the world's water is drawn
// a FIRST time with a GREATER depth test, seed the depth buffer so that only the sea BEYOND a pocket's exit can
// pass -- and only on pixels that look out of the pocket at all (a window: the exit lies before the opaque
// scene). Every other pixel is left at the clear value, 1.0, which nothing beats under GREATER.

uniform sampler2D SavedDepth;
uniform sampler2D ExitDepth;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float exit = texelFetch(ExitDepth, px, 0).r;
    if (exit >= 1.0) {
        discard; // no pocket on this pixel
    }
    float saved = texelFetch(SavedDepth, px, 0).r;
    const float slack = 1.0e-6;
    if (exit >= saved - slack) {
        discard; // the pocket ends on an opaque wall: nothing to see beyond it
    }
    gl_FragDepth = exit;
    fragColor = vec4(0.0);
}
