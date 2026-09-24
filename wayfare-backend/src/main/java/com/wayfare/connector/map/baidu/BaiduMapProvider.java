package com.wayfare.connector.map.baidu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.common.config.CacheConfig;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.governance.CircuitBreaker;
import com.wayfare.connector.governance.ExternalHttpClient;
import com.wayfare.connector.governance.ExternalHttpException;
import com.wayfare.connector.map.MapProvider;
import com.wayfare.connector.map.MapProviderException;
import com.wayfare.connector.map.MapProperties;
import com.wayfare.connector.map.PoiDTO;
import com.wayfare.connector.map.PoiQueryDTO;
import com.wayfare.connector.map.RouteDTO;
import com.wayfare.connector.map.RouteQueryDTO;
import com.wayfare.entity.PoiCache;
import com.wayfare.mapper.PoiCacheMapper;
import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 百度地图 Provider（三级降级里的第一级：实时调用，mode=VERIFIED）。
 *
 * <h3>接口地址与参数（已用真实 AK 实测核对，不是照文档猜的）</h3>
 * <ul>
 *   <li>地点检索：{@code GET /place/v2/search} 参数 {@code query|tag, region, output=json, scope=2,
 *       page_num, page_size, ak}</li>
 *   <li>地点详情：{@code GET /place/v2/detail} 参数 {@code uid, output=json, scope=2, ak}</li>
 *   <li>路线规划：{@code GET /directionlite/v1/{driving|walking|riding|transit}}
 *       参数 {@code origin, destination, ak}</li>
 * </ul>
 *
 * <h3>四个实测确认的坑（照文档写必错）</h3>
 * <ol>
 *   <li><b>{@code page_num} 从 0 开始</b>（不是 1），{@code page_size} 上限 20。
 *       本类对外统一用「从 1 开始」的语义，在这里做转换 —— 否则第一页永远拿不到。</li>
 *   <li><b>请求参数 {@code origin} 是 "lat,lng"（纬度在前）</b>，
 *       而<b>响应里</b>却是 {@code {lng:..., lat:...}} 的对象。
 *       同一家厂商「请求纬度在前、响应经度在前」，凭直觉写必然错一半。
 *       转换只在本类做，绝不让顺序问题散落到业务代码。</li>
 *   <li><b>评分只在 {@code scope=2} 时才有</b>，位于 {@code detail_info.overall_rating}，
 *       而且是个<b>字符串</b>（如 "4.8"）。不加 scope 时连 {@code detail_info} 都没有。</li>
 *   <li><b>百度基本不返回票价</b>。{@code ticketPrice} 取不到就<b>保持 null</b>，
 *       绝不填 0 —— 0 元门票和「未知」是完全不同的两件事，用 0 冒充是数据造假。</li>
 * </ol>
 *
 * <p>另外：AK 只允许来自配置（sys_config → application.yml），<b>任何日志都不打 AK</b>。
 */
@Component
public class BaiduMapProvider implements MapProvider {

    private static final Logger log = LoggerFactory.getLogger(BaiduMapProvider.class);

    public static final String PROVIDER_NAME = "baidu";

    /** 百度 page_size 上限 */
    private static final int MAX_PAGE_SIZE = 20;

    private final ObjectMapper objectMapper;
    private final ExternalHttpClient httpClient;
    private final MapProperties mapProperties;
    private final SysConfigService sysConfigService;
    private final CircuitBreaker circuitBreaker;
    private final PoiCacheMapper poiCacheMapper;

    public BaiduMapProvider(ObjectMapper objectMapper,
                            ExternalHttpClient httpClient,
                            MapProperties mapProperties,
                            SysConfigService sysConfigService,
                            CircuitBreaker circuitBreaker,
                            PoiCacheMapper poiCacheMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.mapProperties = mapProperties;
        this.sysConfigService = sysConfigService;
        this.circuitBreaker = circuitBreaker;
        this.poiCacheMapper = poiCacheMapper;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        // 熔断打开期间即使 AK 正常也不算可用 —— 决策器据此直接走缓存/估算
        return StringUtils.hasText(resolveAk()) && !circuitBreaker.isOpen(PROVIDER_NAME);
    }

    @Override
    public String unavailableReason() {
        if (!StringUtils.hasText(resolveAk())) {
            return "未配置百度地图 AK（检查 sys_config.map.baidu.ak 或 .env.properties 的 BAIDU_MAP_AK）";
        }
        if (circuitBreaker.isOpen(PROVIDER_NAME)) {
            return "百度地图连续失败已触发熔断，暂不发起请求";
        }
        return null;
    }

    /**
     * AK 读取顺序（手册规定）：sys_config（L2） → application.yml（L1） → 空则不可用。
     * 顺序体现的正是「L2 覆盖 L1」：临时换 AK 不用重启。
     */
    private String resolveAk() {
        String fromDb = sysConfigService.get("map.baidu.ak");
        if (StringUtils.hasText(fromDb)) return fromDb.trim();
        return mapProperties.getBaidu() != null ? mapProperties.getBaidu().getAk() : null;
    }

    private String baseUrl() {
        String base = mapProperties.getBaidu() != null ? mapProperties.getBaidu().getBaseUrl() : null;
        return StringUtils.hasText(base) ? base : "https://api.map.baidu.com";
    }

    private int timeoutMs() {
        return mapProperties.getBaidu() != null && mapProperties.getBaidu().getReadTimeoutMs() != null
                ? mapProperties.getBaidu().getReadTimeoutMs() : 8000;
    }

    // ==================== 地点检索 ====================

    /**
     * 地点检索。<b>带 Redis 缓存（24h）</b>：同样的 city+keyword+分页 第二次不再打百度。
     *
     * <p>缓存键 = {@code map:poi:baidu:{city}:{keyword}:{pageNum}:{pageSize}}，
     * 前缀由 {@code CacheConfig} 的 computePrefixWith 拼成，所以线上可以直接用 KEYS 查。
     * 命中缓存时本方法体根本不执行 —— 连 poi_cache 的落库也一并跳过（正是想要的效果）。
     */
    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_MAP_POI,
            key = "'baidu:' + #query.city + ':' + #query.keyword + ':' + #query.pageNum + ':' + #query.pageSize",
            // ⚠️ unless 必须同时挡住 null 与**空列表**：
            //   CacheConfig.disableCachingNullValues() 只对 null 生效，对空 List 无效，
            //   结果「该城市搜不到这个关键词」会被缓存 24 小时（CACHE_MAP_POI 的 TTL），
            //   期间即使百度侧已能搜到也永远命中这个空缓存 —— 表现为「有些点位怎么都搜不出来」。
            //   detail() 早就有 unless = "#result == null"，POI 检索这一处当初漏了。
            unless = "#result == null || #result.isEmpty()")
    public List<PoiDTO> searchPoi(PoiQueryDTO query) {
        if (query == null || (!StringUtils.hasText(query.getKeyword()) && !StringUtils.hasText(query.getTag()))) {
            throw new MapProviderException(ResultCode.MAP_REQUEST_FAILED, PROVIDER_NAME, "关键词与标签至少填一个");
        }
        if (!StringUtils.hasText(query.getCity())) {
            throw new MapProviderException(ResultCode.MAP_REQUEST_FAILED, PROVIDER_NAME, "region(城市) 是百度检索的必填项");
        }

        // 对外 pageNum 从 1 开始，百度从 0 开始 —— 在这里减一
        int pageNum = (query.getPageNum() == null || query.getPageNum() < 1) ? 0 : query.getPageNum() - 1;
        int pageSize = (query.getPageSize() == null || query.getPageSize() < 1)
                ? 10 : Math.min(query.getPageSize(), MAX_PAGE_SIZE);

        StringBuilder url = new StringBuilder(baseUrl()).append("/place/v2/search?");
        if (StringUtils.hasText(query.getKeyword())) {
            url.append("query=").append(enc(query.getKeyword())).append("&");
        }
        if (StringUtils.hasText(query.getTag())) {
            url.append("tag=").append(enc(query.getTag())).append("&");
        }
        url.append("region=").append(enc(query.getCity()))
                .append("&output=json")
                // scope=2 才会返回 detail_info（评分、营业时间都在里面）
                .append("&scope=2")
                .append("&page_num=").append(pageNum)
                .append("&page_size=").append(pageSize)
                .append("&ak=").append(resolveAk());

        JsonNode root = callBaidu(url.toString(), "place/v2/search");
        List<PoiDTO> result = new ArrayList<>();
        JsonNode results = root.path("results");
        if (results.isArray()) {
            for (JsonNode node : results) {
                PoiDTO poi = toPoi(node, query.getCity());
                if (poi != null) result.add(poi);
            }
        }
        // 顺手把查到的 POI 落进本地缓存，这样熔断/断网时二级降级才有数据可用
        cachePois(result, query.getCity());
        return result;
    }

    /**
     * 地点详情。<b>带 Redis 缓存（7d）</b>：POI 的评分/营业时间变化很慢，
     * 缓存久一点能显著省配额。查不到（null）不缓存，避免把「暂时查不到」固化一周。
     */
    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_MAP_DETAIL, key = "#poiUid", unless = "#result == null")
    public PoiDTO detail(String poiUid) {
        if (!StringUtils.hasText(poiUid)) return null;
        String url = baseUrl() + "/place/v2/detail?uid=" + enc(poiUid)
                + "&output=json&scope=2&ak=" + resolveAk();
        JsonNode root = callBaidu(url, "place/v2/detail");
        JsonNode node = root.path("result");
        if (node.isMissingNode() || node.isNull()) return null;
        PoiDTO poi = toPoi(node, null);
        if (poi != null) cachePois(List.of(poi), null);
        return poi;
    }

    // ==================== 路线规划 ====================

    @Override
    public RouteDTO route(RouteQueryDTO query) {
        if (query == null || query.getFromLng() == null || query.getFromLat() == null
                || query.getToLng() == null || query.getToLat() == null) {
            throw new MapProviderException(ResultCode.MAP_REQUEST_FAILED, PROVIDER_NAME, "起点或终点坐标缺失");
        }

        String mode = normalizeMode(query.getMode());
        // 关键：百度要 "lat,lng"，而我们的 DTO 是 lng,lat —— 这里必须交换
        String origin = query.getFromLat() + "," + query.getFromLng();
        String destination = query.getToLat() + "," + query.getToLng();

        String url = baseUrl() + "/directionlite/v1/" + mode
                + "?origin=" + origin + "&destination=" + destination + "&ak=" + resolveAk();

        JsonNode root = callBaidu(url, "directionlite/v1/" + mode);
        JsonNode routes = root.path("result").path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            return null; // 算不出路线不算异常，上层标 ESTIMATED 即可
        }
        JsonNode route = routes.get(0);

        StringBuilder polyline = new StringBuilder();
        JsonNode steps = route.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                String path = step.path("path").asText("");
                if (!path.isEmpty()) {
                    if (polyline.length() > 0) polyline.append(";");
                    polyline.append(path);
                }
            }
        }

        return new RouteDTO(
                route.hasNonNull("distance") ? route.get("distance").asInt() : null,
                route.hasNonNull("duration") ? route.get("duration").asInt() : null,
                mode,
                polyline.toString(),
                false);
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) return "driving";
        String m = mode.trim().toLowerCase();
        return switch (m) {
            case "walking", "riding", "transit", "driving" -> m;
            // 公交在县域常常没有数据，退化为驾车（P1-C 只做映射，业务语义留给 P3 处理）
            default -> "driving";
        };
    }

    // ==================== 公共：调用与解析 ====================

    /**
     * 发请求 + 校验百度 status + 记录熔断。
     *
     * <p>熔断的「写」放在这里、放「读」在决策器里 —— 谁真的发请求谁负责记录，
     * 这样责任清晰，也不会出现「决策器记了一笔它没发起的失败」。
     */
    private JsonNode callBaidu(String url, String apiName) {
        String body;
        try {
            body = httpClient.getJson(url, Map.of(), timeoutMs());
        } catch (ExternalHttpException e) {
            circuitBreaker.recordFailure(PROVIDER_NAME);
            throw new MapProviderException(ResultCode.MAP_REQUEST_FAILED, PROVIDER_NAME,
                    apiName + " 请求失败：" + e.getMessage(), null, e);
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            int status = root.path("status").asInt(-1);
            if (status != 0) {
                circuitBreaker.recordFailure(PROVIDER_NAME);
                String message = root.path("message").asText("");
                throw new MapProviderException(mapBaiduStatus(status, message), PROVIDER_NAME,
                        apiName + " 返回 status=" + status + "（" + message + "）", status);
            }
            // 业务成功才算熔断器意义上的成功
            circuitBreaker.recordSuccess(PROVIDER_NAME);
            return root;
        } catch (MapProviderException e) {
            throw e;
        } catch (Exception e) {
            circuitBreaker.recordFailure(PROVIDER_NAME);
            throw new MapProviderException(ResultCode.MAP_REQUEST_FAILED, PROVIDER_NAME,
                    "响应解析失败：" + e.getMessage(), null, e);
        }
    }

    /**
     * 百度 status 码 → 项目业务码。
     *
     * <p><b>为什么还要看 message 文案兜底</b>：百度的 status 码在不同接口/不同版本上并不统一。
     * 实测踩到：AK 填错时 {@code place/v2/search} 返回的是
     * <b>{@code status=200, message="APP不存在，AK有误请检查再重试"}</b> —— 200 在别处是成功码，
     * 在这里却是错误码。如果只按码表猜，这种「AK 错」会被报成含糊的「请求失败」，
     * 管理员根本不知道该去查 AK。所以码表之外再按文案关键词兜一次。
     *
     * <p>原则：宁可分不清时归到「请求失败」，也不要把普通错误瞎报成「认证失败」——
     * 后者会让管理员白折腾一轮 AK。
     */
    private ResultCode mapBaiduStatus(int status, String message) {
        ResultCode byCode = switch (status) {
            // 3=权限校验失败、5=AK 不存在或被删除、101=AK 无效、200=实测的「APP不存在，AK有误」
            case 3, 5, 101, 200 -> ResultCode.MAP_AUTH_FAIL;
            // 4=配额校验失败、302=天配额超限、401=并发超限、402=配额不足
            case 4, 302, 401, 402 -> ResultCode.MAP_QUOTA_EXCEEDED;
            default -> null;
        };
        if (byCode != null) {
            return byCode;
        }

        String m = message == null ? "" : message;
        if (m.contains("AK") || m.contains("ak") || m.contains("权限") || m.contains("APP不存在")) {
            return ResultCode.MAP_AUTH_FAIL;
        }
        if (m.contains("配额") || m.contains("超限")) {
            return ResultCode.MAP_QUOTA_EXCEEDED;
        }
        return ResultCode.MAP_REQUEST_FAILED;
    }

    /**
     * 把百度的一条结果转成 PoiDTO。
     *
     * <p>字段缺失一律留 null / 默认值，<b>不做任何「补一个看起来合理的数」的猜测</b>。
     */
    private PoiDTO toPoi(JsonNode node, String city) {
        if (node == null || node.isMissingNode()) return null;

        Double lng = null, lat = null;
        JsonNode location = node.path("location");
        if (location.isObject()) {
            if (location.hasNonNull("lng")) lng = location.get("lng").asDouble();
            if (location.hasNonNull("lat")) lat = location.get("lat").asDouble();
        }

        JsonNode detail = node.path("detail_info");

        String tag = null;
        if (detail.hasNonNull("tag")) {
            tag = detail.get("tag").asText();
        } else if (detail.hasNonNull("classified_poi_tag")) {
            tag = detail.get("classified_poi_tag").asText();
        }

        String shopHours = detail.hasNonNull("shop_hours") ? detail.get("shop_hours").asText() : null;

        // overall_rating 是字符串（如 "4.8"）；解析不出来就保持 null，不填 0
        Double rating = parseDoubleOrNull(detail.hasNonNull("overall_rating")
                ? detail.get("overall_rating").asText() : null);
        // ★ 实测踩到的坑：百度没有评分时不是「不返回该字段」，而是返回字符串 "0"。
        //   直接转成 0.0 会被读成「0 分」—— 那是编造数据，且比缺字段更危险（前端会显示 0 分而不是「未知」）。
        //   百度的评分体系是 1~5 分，0 分不是一个真实取值，所以 <=0 一律视为「未知」。
        if (rating != null && rating <= 0) {
            rating = null;
        }

        // 票价：百度多数场景不返回。有 price 时尝试解析，取不到就 null。
        // 注意这里刻意不套用 rating 的「<=0 视为未知」规则 ——
        // 门票 0 元（免费景点）是一个真实且有意义的取值，而 0 分不是。
        Double ticketPrice = parseDoubleOrNull(detail.hasNonNull("price")
                ? detail.get("price").asText() : null);

        return new PoiDTO(
                node.path("uid").asText(null),
                node.path("name").asText(""),
                node.path("address").asText(""),
                lng,
                lat,
                tag,
                shopHours,
                rating,
                ticketPrice,
                node.toString());
    }

    /**
     * 宽松解析数字：从 "4.8"、"¥50"、"50元" 这类字符串里抠出数字。
     * <b>抠不出来返回 null 而不是 0</b> —— null 表示未知，0 表示真的是零，两者不能混。
     */
    private Double parseDoubleOrNull(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?").matcher(raw);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 把 POI 写进本地缓存（best-effort：失败只记日志，绝不影响本次检索结果）。
     * 按 poi_uid upsert，所以重复检索不会堆积数据。
     *
     * <p><b>为什么是「先删再插」而不是 updateById</b>：
     * MyBatis-Plus 的 {@code updateById} 默认<b>跳过值为 null 的字段</b>（FieldStrategy.NOT_NULL）。
     * 而本表的 {@code rating} / {@code ticket_price} 恰恰<b>允许为 null 且 null 是有意义的值</b>
     * （表示「百度没给这个数据」）。用 updateById 就会出现：
     * 上一次存了 0.0、这一次算出 null、结果旧值 0.0 纹丝不动 —— 实测踩过这个坑。
     * 删了重插则 null 会被如实写入，语义清晰，代价只是 id 会变（对缓存表无所谓）。
     */
    private void cachePois(List<PoiDTO> pois, String city) {
        if (pois == null || pois.isEmpty()) return;
        for (PoiDTO poi : pois) {
            if (!StringUtils.hasText(poi.poiUid())) continue;
            try {
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PoiCache> wrapper =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
                wrapper.eq(PoiCache::getPoiUid, poi.poiUid());
                PoiCache existing = poiCacheMapper.selectOne(wrapper);

                String effectiveCity = city != null ? city
                        : (existing != null ? existing.getCity() : "");
                if (existing != null) {
                    poiCacheMapper.deleteById(existing.getId());
                }

                PoiCache entity = new PoiCache();
                entity.setPoiUid(poi.poiUid());
                entity.setName(poi.name());
                entity.setAddress(poi.address());
                entity.setCity(effectiveCity);
                entity.setLng(poi.lng());
                entity.setLat(poi.lat());
                entity.setTag(poi.tag());
                entity.setShopHours(poi.shopHours());
                entity.setRating(poi.rating());
                entity.setTicketPrice(poi.ticketPrice());
                entity.setRawJson(poi.rawJson());
                poiCacheMapper.insert(entity);
            } catch (Exception e) {
                log.warn("写 POI 缓存失败（不影响检索结果）: poiUid={}, {}", poi.poiUid(), e.getMessage());
            }
        }
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
