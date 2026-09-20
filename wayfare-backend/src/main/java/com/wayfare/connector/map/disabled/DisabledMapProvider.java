package com.wayfare.connector.map.disabled;

import com.wayfare.connector.map.MapProvider;
import com.wayfare.connector.map.PoiDTO;
import com.wayfare.connector.map.PoiQueryDTO;
import com.wayfare.connector.map.RouteDTO;
import com.wayfare.connector.map.RouteQueryDTO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 关闭态地图 Provider（三级降级里的第三级：mode=ESTIMATED）。
 *
 * <p>管理员在后台把 {@code map.enabled} 关掉后，由本实现接管所有地图调用：
 * 不查库、不联网、不报错，直接返回空数据。上层拿到空数据后会走估算，
 * 并在结果上标记 {@code ESTIMATED}。
 *
 * <p><b>这是项目铁律「连接器可插拔」的落点</b>：
 * 关闭地图之后，行程规划、攻略生成、发布浏览<b>全都不受影响</b>，
 * 只是距离与时长变成估算值。所以它叫<b>能力降级</b>，不是功能降级 ——
 * 也正因如此，本类的方法必须「安静地返回空」而不是抛异常。
 */
@Component
public class DisabledMapProvider implements MapProvider {

    public static final String PROVIDER_NAME = "disabled";

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        // 恒 false：它代表「地图能力不可用」这个状态本身
        return false;
    }

    @Override
    public String unavailableReason() {
        return "地图能力已被管理员整体关闭，距离与时长将使用估算值(ESTIMATED)";
    }

    @Override
    public List<PoiDTO> searchPoi(PoiQueryDTO query) {
        return Collections.emptyList();
    }

    @Override
    public PoiDTO detail(String poiUid) {
        return null;
    }

    @Override
    public RouteDTO route(RouteQueryDTO query) {
        return null;
    }
}
