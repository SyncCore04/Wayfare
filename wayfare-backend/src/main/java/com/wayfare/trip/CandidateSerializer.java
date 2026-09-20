package com.wayfare.trip;

import com.wayfare.dto.CandidateDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 候选池 → 紧凑文本（P3-B）。
 *
 * <p>P3-D 的行程编排要把候选池整个塞进 prompt，让模型「只能从这里选点」。
 * 所以这个格式有两个硬要求：
 * <ol>
 *   <li><b>必须带编号</b> —— 模型选点时引用编号比引用长名称可靠得多（名称可能被它改写），
 *       P3-E 的 CLOSURE 校验也是拿编号/名称回查池子。</li>
 *   <li><b>必须紧凑</b> —— 20 个点如果每个都写全字段，光候选就吃掉几千 token。
 *       只留模型做决策真正需要的：名称、类型、区域、坐标、建议停留、亮点。</li>
 * </ol>
 *
 * <p><b>没有坐标时输出「坐标未知」而不是省略这一列</b>：
 * 省略会让模型以为「这一栏本来就不存在」，而写明「未知」是在告诉它
 * 「这个点的位置我拿不到，你排顺序时别假设它离得近」—— 地图关闭时全靠这句话兜住。
 */
@Component
public class CandidateSerializer {

    /**
     * 输出形如：
     * <pre>
     * [1] 寿阳文庙 | 景点 | 寿阳县城内 | 113.17,37.89 | 90分钟 | 金代木构，晋中保存最完整
     * [2] 龙栖湖 | 景点 | 寿阳县西部 | 坐标未知 | 120分钟 | 湖光山色，适合半日游
     * </pre>
     *
     * @param items 候选点，可为 null（返回空串，调用方据此跳过注入）
     */
    public String serialize(List<CandidateDTO> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (CandidateDTO item : items) {
            sb.append('[').append(index++).append("] ")
              .append(blankTo(item.getName(), "未命名")).append(" | ")
              .append(typeLabel(item.getItemType())).append(" | ")
              .append(blankTo(item.getArea(), "区域未知")).append(" | ")
              .append(locationText(item)).append(" | ")
              .append(item.getStayMinutes() == null ? "时长未定" : item.getStayMinutes() + "分钟").append(" | ")
              .append(blankTo(item.getHighlight(), "暂无亮点说明"))
              .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 坐标列：有坐标给数值，没坐标明确写「未知」——见类注释 */
    private String locationText(CandidateDTO item) {
        if (!item.hasLocation()) {
            return "坐标未知";
        }
        return trimNumber(item.getLng()) + "," + trimNumber(item.getLat());
    }

    /** 去掉 Double 的小数尾巴（113.17000000001 这种会浪费 token 且看着不专业） */
    private String trimNumber(Double value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (text.endsWith(".0")) {
            return text.substring(0, text.length() - 2);
        }
        return text;
    }

    /** itemType 翻译成模型和用户都看得懂的中文 */
    private String typeLabel(String itemType) {
        if (itemType == null) return "未知";
        return switch (itemType) {
            case "FOOD" -> "餐饮";
            case "SCENIC" -> "景点";
            case "HOTEL" -> "住宿";
            case "TRANSPORT" -> "交通";
            case "REST" -> "休息";
            default -> itemType;
        };
    }

    private String blankTo(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
