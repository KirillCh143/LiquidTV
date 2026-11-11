package com.gathin.tvhome;

/**
 * Local patch: the AGSL for the clock screensaver's glass digits (LiquidClockLayout, API 33+). The same lens as the
 * dock's LiquidGlassShader — an edge band where the wallpaper behind is displaced along the shape's normal, a faint
 * prism fringe and a soft sheen on the top-left edges — but the shape is the text, so its distance field is not a
 * formula: the shape's outline and normal come from sampling {@code mask} (the text drawn as alpha) in a ring around
 * each pixel. Chained onto the same vibrancy + blur RenderEffect the dock uses (input {@code content}).
 */
final class TextGlassShader {
    private TextGlassShader() { }

    /** Uniforms: float refractionHeight (the digits' edge band, px); float refractionHeightSmall (the AM/PM label's); float refractionAmount (peak displacement at the
     *  edge, px). Shaders: content (the blurred wallpaper), mask (the text: its alpha is the coverage, red marks the
     *  digits and green the small label, same pixel space). */
    static final String SOURCE = ""
            + "uniform shader content;\n"
            + "uniform shader mask;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionHeightSmall;\n"
            + "uniform float refractionAmount;\n"
            + "\n"
            + "float cover(float2 p) {\n"
            + "    return mask.eval(p).a;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    half4 here = mask.eval(coord);\n"
            + "    float inside = here.a;\n"
            + "    if (inside < 0.01) {\n"
            + "        return half4(0.0);\n"
            + "    }\n"
            // The mask is red under the digits and green under the small AM/PM label, which has its own, thinner band.
            + "    float small = step(here.r + 0.001, here.g);\n"
            + "    float r = max(mix(refractionHeight, refractionHeightSmall, small), 1.0);\n"
            // Twelve taps on two rings: their mean says how deep inside the glyph this pixel is (about 0.5 right at
            // an edge, 1 well inside), and their sum, weighted by direction, points to the inside.
            + "    float2 toward = float2(0.0, 0.0);\n"
            + "    float sum = 0.0;\n"
            + "    for (int i = 0; i < 12; i++) {\n"
            + "        float angle = 6.2831853 * float(i) / 12.0;\n"
            + "        float2 dir = float2(cos(angle), sin(angle));\n"
            + "        float s = cover(coord + dir * r * 0.6) + cover(coord + dir * r * 1.6);\n"
            + "        toward += dir * s;\n"
            + "        sum += s;\n"
            + "    }\n"
            + "    float depth = clamp((sum / 24.0 - 0.5) * 2.0, 0.0, 1.0);\n"
            + "    float edge = 1.0 - depth;\n"
            + "    float2 grad = float2(0.0, 0.0);\n"
            + "    if (length(toward) > 0.001) {\n"
            + "        grad = -normalize(toward);\n"
            + "    }\n"
            + "\n"
            + "    float d = edge * refractionAmount;\n"
            + "    float2 refracted = coord - d * grad;\n"
            + "    half4 base = content.eval(refracted);\n"
            + "\n"
            + "    float fringe = edge * 0.3;\n"
            + "    half4 warm = content.eval(refracted - grad * fringe * 2.0);\n"
            + "    half4 cool = content.eval(refracted + grad * fringe * 2.0);\n"
            + "    base.r = mix(base.r, warm.r, fringe);\n"
            + "    base.b = mix(base.b, cool.b, fringe);\n"
            + "\n"
            + "    float2 lightDir = normalize(float2(-0.5, -1.0));\n"
            + "    float highlight = max(dot(grad, lightDir), 0.0) * edge;\n"
            // The sheen on the lit edge only; no frosted lift across the glyph.
            + "    base.rgb += half3(highlight * 0.45);\n"
            + "    return half4(base.rgb, 1.0);\n"
            + "}\n";
}
