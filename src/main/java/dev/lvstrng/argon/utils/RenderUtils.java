package dev.lvstrng.argon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.*;

public class RenderUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public static void drawOutlineBox(MatrixStack matrices, Box box, Color color, float lineWidth) {
        renderBox(matrices, box, color, lineWidth, false);
    }

    public static void drawFilledBox(MatrixStack matrices, Box box, Color color) {
        renderBox(matrices, box, color, 0.0f, true);
    }

    private static void renderBox(MatrixStack matrices, Box box, Color color, float lineWidth, boolean fill) {
        Vec3d camPos = mc.gameRenderer.getCamera().getPos();
        double x = box.minX - camPos.x;
        double y = box.minY - camPos.y;
        double z = box.minZ - camPos.z;

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        if (fill) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.disableDepthTest();

            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            // Draw all 6 faces of the box
            drawBoxFaces(matrix, buffer, x, y, z, box.maxX - camPos.x, box.maxY - camPos.y, box.maxZ - camPos.z, color);
            tessellator.draw();

            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
        } else {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.lineWidth(lineWidth);
            Render
