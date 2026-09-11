#version 330

// Puts the pre-water colour and depth back on every pixel where the world's translucent pass landed a
// fragment INSIDE a submarine's dry pocket. See ArmadaPocketOccluder:
//   SavedColor / SavedDepth  -- the frame before the world's translucent pass
//   CurrentDepth             -- the depth after it
//   FrontDepth / BackDepth   -- the pocket's span along this pixel's ray (nearest and farthest boundary face;
//                               FrontDepth is 0 everywhere while the camera itself is inside a pocket, and
//                               BackDepth is 0 wherever no pocket covers the pixel)
// Anything that drew nearer than the pocket, or beyond it, is left exactly as it is.

uniform sampler2D SavedColor;
uniform sampler2D SavedDepth;
uniform sampler2D CurrentDepth;
uniform sampler2D FrontDepth;
uniform sampler2D BackDepth;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float current = texelFetch(CurrentDepth, px, 0).r;
    float saved = texelFetch(SavedDepth, px, 0).r;
    if (current >= saved) {
        discard; // nothing translucent landed nearer than the scene here
    }
    float back = texelFetch(BackDepth, px, 0).r;
    if (back <= 0.0) {
        discard; // no pocket on this pixel
    }
    float front = texelFetch(FrontDepth, px, 0).r;
    // A hair of slack: the water's plane meets the boundary exactly where it enters a wall, and the two
    // depths come from different geometry.
    const float slack = 1.0e-6;
    if (current < front - slack || current > back + slack) {
        discard; // the translucent fragment is outside the pocket
    }
    gl_FragDepth = saved;
#ifdef POCKET_DEBUG
    fragColor = vec4(0.0, 1.0, 0.0, 1.0);
#else
    fragColor = texelFetch(SavedColor, px, 0);
#endif
}
