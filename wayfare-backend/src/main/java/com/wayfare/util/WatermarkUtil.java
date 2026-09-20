package com.wayfare.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 图片水印工具类
 * 使用Java 2D API添加文字水印
 */
public class WatermarkUtil {

    private static final Logger log = LoggerFactory.getLogger(WatermarkUtil.class);

    /**
     * 给图片添加文字水印
     *
     * @param sourceFile 源图片文件
     * @param targetFile 目标图片文件
     * @param watermarkText 水印文字
     * @param fontSize 字体大小
     * @param opacity 透明度(0-1)
     * @param colorStr 颜色RGB，格式 "255,255,255"
     * @return 是否成功
     */
    public static boolean addTextWatermark(File sourceFile, File targetFile,
                                            String watermarkText, int fontSize,
                                            float opacity, String colorStr) {
        try {
            BufferedImage image = ImageIO.read(sourceFile);
            if (image == null) {
                log.error("无法读取图片: {}", sourceFile.getName());
                return false;
            }

            int width = image.getWidth();
            int height = image.getHeight();

            BufferedImage watermarked = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = watermarked.createGraphics();

            // 抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 绘制原图
            g2d.drawImage(image, 0, 0, null);

            // 解析颜色
            String[] rgb = colorStr.split(",");
            Color color = new Color(Integer.parseInt(rgb[0].trim()),
                    Integer.parseInt(rgb[1].trim()),
                    Integer.parseInt(rgb[2].trim()));

            // 设置水印字体和透明度
            g2d.setFont(new Font("微软雅黑", Font.BOLD, fontSize));
            g2d.setColor(color);
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));

            // 计算水印文字宽度
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(watermarkText);
            int textHeight = fm.getHeight();

            // 右下角绘制水印，留出边距
            int x = width - textWidth - 30;
            int y = height - 30;

            // 绘制文字阴影（增强可读性）
            g2d.setColor(new Color(0, 0, 0, (int)(opacity * 128)));
            g2d.drawString(watermarkText, x + 2, y + 2);

            // 绘制水印文字
            g2d.setColor(color);
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
            g2d.drawString(watermarkText, x, y);

            g2d.dispose();

            // 获取文件扩展名
            String ext = getFileExtension(sourceFile.getName());
            if ("jpg".equalsIgnoreCase(ext) || "jpeg".equalsIgnoreCase(ext)) {
                ext = "jpg";
            }

            // 写入目标文件
            boolean result = ImageIO.write(watermarked, ext, targetFile);
            if (result) {
                log.info("水印添加成功: {} -> {}", sourceFile.getName(), targetFile.getName());
            }
            return result;

        } catch (IOException e) {
            log.error("添加水印失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取图片尺寸
     */
    public static int[] getImageDimensions(File imageFile) {
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image != null) {
                return new int[]{image.getWidth(), image.getHeight()};
            }
        } catch (IOException e) {
            log.error("读取图片尺寸失败: {}", e.getMessage());
        }
        return new int[]{0, 0};
    }

    /**
     * 获取文件扩展名
     */
    private static String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1).toLowerCase();
        }
        return "png";
    }
}
