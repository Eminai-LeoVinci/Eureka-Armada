#version 330

// Shaderpack path, last step (see ArmadaPocketOccluder): the world's water has now been drawn twice -- once beyond
// the pockets' exits, once in front of their entries -- and the depth buffer holds the second draw's result on
// top of the entry seed. Per pocket pixel: water that landed in front of the entry stays; a pixel the second
// draw left untouched (still exactly at the entry seed) gets whatever the first draw left beyond the exit, or
// the opaque scene. Pixels no pocket covers keep the second draw's depth unchanged.

uniform sampler2D SavedDepth;
uniform sampler2D FrontDepth;
uniform sampler2D ExitDepth;
uniform sampler2D AfterADepth;
uniform sampler2D CurrentDepth;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float current = texelFetch(CurrentDepth, px, 0).r;
    float saved = texelFetch(SavedDepth, px, 0).r;
    float front = texelFetch(FrontDepth, px, 0).r;
    float exit = texelFetch(ExitDepth, px, 0).r;
    float afterA = texelFetch(AfterADepth, px, 0).r;
    const float slack = 1.0e-6;
    bool pocket = exit < 1.0 && front < saved;
    bool window = pocket && exit < saved - slack;
    // The first draw wrote beyond the exit only where a window looks out; anything it left at the seed means
    // no water there. Never farther than the opaque scene.
    float beyond = (window && afterA != exit) ? min(afterA, saved) : saved;
    float depth = (pocket && current == front) ? beyond : current;
    if (depth >= 1.0) {
        discard; // the clear already holds 1.0
    }
    gl_FragDepth = depth;
    fragColor = vec4(0.0);
}
