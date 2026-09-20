package com.wayfare.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MaskUtil} 单元测试（P1-D 验收 1）。
 *
 * <p>脱敏是这个项目里「一旦漏一次就不可挽回」的地方 —— 密钥进了日志、工单、截图就收不回来了。
 * 所以这里不只测手册点名的那个用例，把 URL / JSON / Bearer 三种形态都覆盖上。
 */
class MaskUtilTest {

    @Test
    @DisplayName("验收1：maskSecret(\"sk-abcdefghijklmn\") 返回 \"sk-a****\"")
    void maskSecretKeepsFirstFourChars() {
        assertEquals("sk-a****", MaskUtil.maskSecret("sk-abcdefghijklmn"));
    }

    @Test
    @DisplayName("边界：null / 空串 / 长度≤4 都不泄露内容")
    void maskSecretEdgeCases() {
        assertEquals("", MaskUtil.maskSecret(null));
        assertEquals("", MaskUtil.maskSecret(""));
        // 长度 ≤4 时前 4 位就是全部内容，只能整个掩掉
        assertEquals("****", MaskUtil.maskSecret("abc"));
        assertEquals("****", MaskUtil.maskSecret("abcd"));
        // 长度 5 才保留前 4 位
        assertEquals("abcd****", MaskUtil.maskSecret("abcde"));
    }

    @Test
    @DisplayName("URL 里的 ak 参数会被脱敏（百度地图的 AK 就在 query 里）")
    void maskUrlSecrets() {
        String url = "https://api.map.baidu.com/place/v2/search?query=x&ak=SECRETAK1234567890&page_size=3";
        String masked = MaskUtil.maskUrlSecrets(url);
        assertFalse(masked.contains("SECRETAK1234567890"), "URL 里的完整 AK 必须被掩掉");
        assertTrue(masked.contains("ak=SECR****"));
        // 非敏感参数不能被误伤
        assertTrue(masked.contains("page_size=3"));
    }

    @Test
    @DisplayName("JSON 里的 api_key / token 字段会被脱敏")
    void maskJsonSecrets() {
        String json = "{\"model\":\"glm-4-flash\",\"api_key\":\"abcdef1234567890\",\"temperature\":0.3}";
        String masked = MaskUtil.maskJsonSecrets(json);
        assertFalse(masked.contains("abcdef1234567890"));
        assertTrue(masked.contains("\"api_key\":\"abcd****\""));
        assertTrue(masked.contains("glm-4-flash"), "非敏感字段应原样保留");
    }

    @Test
    @DisplayName("Authorization: Bearer xxx 形态会被脱敏")
    void maskBearer() {
        String masked = MaskUtil.maskBearer("Authorization: Bearer eyJhbGciOiJIUzM4NCJ9.payload.sig");
        assertFalse(masked.contains("eyJhbGciOiJIUzM4NCJ9.payload.sig"));
        assertTrue(masked.contains("Bearer eyJh****"));
    }

    @Test
    @DisplayName("sanitize：三种形态一次过，并且**先脱敏再截断**")
    void sanitizeMasksBeforeTruncating() {
        String raw = "GET https://api.map.baidu.com/place/v2/search?ak=SECRETAK1234567890&query=" + "x".repeat(1000);
        String out = MaskUtil.sanitize(raw);
        // 先脱敏再截断：否则截断可能把密钥切成前半段留在库里
        assertFalse(out.contains("SECRETAK1234567890"));
        assertTrue(out.length() <= MaskUtil.DEFAULT_MAX_LENGTH + 20, "应被截断到 500 字符附近");
        assertTrue(out.endsWith("...(truncated)"));
    }
}
