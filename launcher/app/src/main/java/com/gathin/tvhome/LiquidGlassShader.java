package com.gathin.tvhome;

/**
 * Local patch, not an upstream feature: the AGSL source for dock_backdrop "liquid", RuntimeShader-only (API 33+,
 * WallpaperPlayer). The rounded-rect SDF, its gradient and the edge-refraction displacement (circleMap easing)
 * are adapted from Kyant0/AndroidLiquidGlass (github.com/Kyant0/AndroidLiquidGlass,
 * backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt), Apache License 2.0 — that library
 * targets Compose Multiplatform and cannot be used here as a dependency (plain Java Views, no new launcher
 * dependencies without an issue first), so this is a from-scratch AGSL string, not its code, just its proven
 * shape math, ported from an element-local coordinate space to this file's own: {@code content} covers the whole
 * screen (WallpaperPlayer's blur layer is match_parent), so the shape lives at an explicit rect uniform instead
 * of a local size/offset pair.
 */
final class LiquidGlassShader {
    private LiquidGlassShader() { }

    /** Uniforms: float2 rectMin, rectMax (the glass shape's rect, screen space); float cornerRadius; float
     *  refractionHeight (how far the edge band reaches in, px); float refractionAmount (peak pixel displacement
     *  right at the edge). Chained onto a blur RenderEffect via RenderEffect.createRuntimeShaderEffect(shader,
     *  "content"). */
    static final String SOURCE = ""
            + "uniform shader content;\n"
            + "uniform float2 rectMin;\n"
            + "uniform float2 rectMax;\n"
            + "uniform float cornerRadius;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "\n"
            + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius, radius));\n"
            + "    float outside = length(max(cornerCoord, float2(0.0, 0.0))) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius, radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, float2(0.0, 0.0)));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n"
            + "\n"
            + "float circleMap(float x) {\n"
            // Local patch: a plain linear ramp, not sqrt(x) (too steep right at the edge) nor the original
            // quarter-circle ease 1 - sqrt(1 - x*x) (too flat until the last few pixels) — linear spreads the
            // bend evenly across the whole band instead of concentrating it at either end.
            + "    return clamp(x, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = (rectMax - rectMin) * 0.5;\n"
            + "    float2 center = rectMin + halfSize;\n"
            + "    float2 centeredCoord = coord - center;\n"
            + "\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, cornerRadius);\n"
            + "    if (-sd >= refractionHeight) {\n"
            + "        return content.eval(coord);\n"
            + "    }\n"
            + "    float clamped = min(sd, 0.0);\n"
            + "    float gradRadius = min(cornerRadius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius));\n"
            + "\n"
            + "    float d = circleMap(1.0 - (-clamped) / refractionHeight) * refractionAmount;\n"
            + "    float2 refracted = coord - d * grad;\n"
            + "    half4 base = content.eval(refracted);\n"
            + "\n"
            + "    // A faint prism fringe right at the edge, like glass dispersing light at its border.\n"
            + "    float fringe = (1.0 - smoothstep(0.0, refractionHeight, -clamped)) * 0.3;\n"
            + "    half4 warm = content.eval(refracted - grad * fringe * 2.0);\n"
            + "    half4 cool = content.eval(refracted + grad * fringe * 2.0);\n"
            + "    base.r = mix(base.r, warm.r, fringe);\n"
            + "    base.b = mix(base.b, cool.b, fringe);\n"
            + "\n"
            + "    // A soft specular sheen along the top-left edge, fading toward the middle.\n"
            + "    float2 lightDir = normalize(float2(-0.5, -1.0));\n"
            + "    float highlight = max(dot(grad, lightDir), 0.0)\n"
            + "            * (1.0 - smoothstep(0.0, refractionHeight * 1.5, -clamped));\n"
            + "    base.rgb += half3(highlight * 0.06);\n"
            + "\n"
            + "    return base;\n"
            + "}\n";
}
