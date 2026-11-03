package com.example.hamkit.data.zone

import com.example.hamkit.data.ZoneInfo
import kotlin.math.floor

/**
 * CQ / ITU 分区解析器。
 *
 * 内置了一套常见国家/地区的经纬度包围盒覆盖，用于在大部分陆地人口密集区获得较准确结果；
 * 未覆盖区域使用基于经度的经验公式作为兜底估算，适合海上或偏远地区。
 * 如需更高精度，可后续替换为从 assets 加载的 GeoJSON 多边形数据。
 */
object ZoneResolver {

    private data class ZoneRegion(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
        val cqZone: Int,
        val ituZone: Int
    ) {
        /**
         * 左开右闭区间 (min, max]：边界点归属上限区域。
         * 极值边界（-180 / -90）作为闭区间下限包含，确保对蹟点仍可命中。
         */
        fun contains(lat: Double, lon: Double): Boolean {
            val latOk = if (minLat <= -90.0) lat >= minLat && lat <= maxLat
                        else lat > minLat && lat <= maxLat
            val lonOk = if (minLon <= -180.0) lon >= minLon && lon <= maxLon
                        else lon > minLon && lon <= maxLon
            return latOk && lonOk
        }
    }

    /**
     * 区域列表按优先级排列（**后覆盖前**）：resolve 用 asReversed() 倒序遍历，
     * 列表尾部（最后添加）的条目最先检查、优先级最高。
     *
     * 因此排列规则是「越大越泛的区域越靠前、越小越精确的区域越靠后」——
     * 曾把跨大洋的热带泛区放在表尾，导致它们压过加勒比/南美/西非的精确
     * 分区（哈瓦那判成太平洋 CQ35、利马判成大西洋 CQ13）；P1-3 修复后
     * 热带泛区已移到列表最前（最先添加、最后检查）。
     */
    private val regions: List<ZoneRegion> = buildList {
        // ── 最泛：跨大洋热带泛区（P1-3 修复：放在列表最前 = asReversed() 下最后检查，
        //    仅当所有更精确的陆地区域都未命中时才兜底，避免压过加勒比/南美/西非精确区） ──
        add(ZoneRegion(-25.0, 25.0, -180.0, -120.0, cqZone = 31, ituZone = 61))
        add(ZoneRegion(-25.0, 25.0, -120.0, -80.0, cqZone = 35, ituZone = 11))
        add(ZoneRegion(-25.0, 25.0, -80.0, -30.0, cqZone = 13, ituZone = 12))
        add(ZoneRegion(-25.0, 25.0, -30.0, 20.0, cqZone = 36, ituZone = 46))
        add(ZoneRegion(-25.0, 25.0, 142.0, 180.0, cqZone = 27, ituZone = 51))

        // 北美（泛框在前，细分在后）。
        // 美国本土按 CQ 实际分界（约西经 85°）拆成 CQ4/CQ5 两条，避免全境
        // 误判成单一分区；ITU 沿用相邻值。
        add(ZoneRegion(24.0, 72.0, -180.0, -50.0, cqZone = 5, ituZone = 8))
        add(ZoneRegion(50.0, 72.0, -141.0, -52.0, cqZone = 2, ituZone = 75)) // 加拿大大部分地区
        add(ZoneRegion(18.0, 50.0, -125.0, -85.0, cqZone = 4, ituZone = 7))  // 美国本土中西部
        add(ZoneRegion(18.0, 50.0, -85.0, -66.0, cqZone = 5, ituZone = 8))   // 美国本土东部
        add(ZoneRegion(18.0, 30.0, -160.0, -154.0, cqZone = 31, ituZone = 61)) // 夏威夷
        add(ZoneRegion(51.0, 72.0, -179.0, -130.0, cqZone = 1, ituZone = 1))  // 阿拉斯加

        // 中美洲与加勒比。
        // P1-3 修复：大框北界收到 19°（原 32° 伸入美国本土，倒挂压过 CQ4 框，德州/新墨西哥
        // 被误判 CQ7/ITU11）；墨西哥框北界收到 29°、经度下限 -88（原 30°/-86 会吞掉
        // 德州圣安东尼奥一带），哥伦比亚框北界收到 8°（原 13° 吞掉巴拿马 CQ7）。
        add(ZoneRegion(5.0, 19.0, -118.0, -59.0, cqZone = 7, ituZone = 11))
        add(ZoneRegion(18.0, 24.0, -84.0, -71.0, cqZone = 8, ituZone = 11))   // 古巴/牙买加
        add(ZoneRegion(14.0, 19.0, -95.0, -77.0, cqZone = 10, ituZone = 10))  // 墨西哥南部/中美洲北侧
        add(ZoneRegion(14.0, 29.0, -115.0, -88.0, cqZone = 10, ituZone = 10)) // 墨西哥本土

        // 南美洲（大框北界收到 8°：原 13° 会以更后添加顺序压过中美洲框，巴拿马被误判 CQ11）
        add(ZoneRegion(-56.0, 8.0, -82.0, -34.0, cqZone = 11, ituZone = 12))
        add(ZoneRegion(-24.0, 6.0, -80.0, -35.0, cqZone = 11, ituZone = 13))  // 巴西
        add(ZoneRegion(-56.0, -20.0, -75.0, -53.0, cqZone = 12, ituZone = 14)) // 阿根廷/智利等
        add(ZoneRegion(-5.0, 7.5, -80.0, -75.0, cqZone = 9, ituZone = 11))   // 哥伦比亚西部（北界 7.5°，避免吞巴拿马东部）
        add(ZoneRegion(-5.0, 12.0, -75.0, -60.0, cqZone = 9, ituZone = 11))  // 哥伦比亚主体/委内瑞拉（含加拉加斯）
        add(ZoneRegion(-5.0, 2.0, -82.0, -75.0, cqZone = 11, ituZone = 12))   // 厄瓜多尔
        add(ZoneRegion(-18.0, -5.0, -82.0, -70.0, cqZone = 10, ituZone = 12)) // 秘鲁/玻利维亚（西海岸）

        // 欧洲
        add(ZoneRegion(36.0, 71.0, -11.0, 40.0, cqZone = 14, ituZone = 27))
        add(ZoneRegion(50.0, 60.0, -8.0, 2.0, cqZone = 14, ituZone = 27))      // 英国/爱尔兰
        add(ZoneRegion(43.0, 51.0, -5.0, 8.0, cqZone = 14, ituZone = 27))      // 法国/比荷卢
        add(ZoneRegion(47.0, 55.0, 5.0, 15.0, cqZone = 14, ituZone = 28))      // 德国/波兰
        add(ZoneRegion(36.0, 44.0, -10.0, 4.0, cqZone = 14, ituZone = 37))     // 西班牙/葡萄牙
        add(ZoneRegion(39.0, 47.0, 6.0, 19.0, cqZone = 15, ituZone = 28))      // 意大利
        add(ZoneRegion(35.0, 42.0, 19.0, 30.0, cqZone = 15, ituZone = 28))     // 希腊/土耳其西
        add(ZoneRegion(55.0, 71.0, 5.0, 32.0, cqZone = 16, ituZone = 29))      // 北欧
        add(ZoneRegion(55.0, 60.0, 20.0, 32.0, cqZone = 15, ituZone = 29))     // 波罗的海
        add(ZoneRegion(59.0, 70.0, 20.0, 45.0, cqZone = 16, ituZone = 29))     // 芬兰/俄罗斯欧洲

        // 俄罗斯/独联体（乌拉尔以东）
        add(ZoneRegion(50.0, 77.0, 40.0, 180.0, cqZone = 16, ituZone = 30))
        add(ZoneRegion(50.0, 70.0, 60.0, 90.0, cqZone = 17, ituZone = 30))
        add(ZoneRegion(50.0, 70.0, 90.0, 120.0, cqZone = 18, ituZone = 31))
        add(ZoneRegion(50.0, 70.0, 120.0, 150.0, cqZone = 19, ituZone = 32))
        add(ZoneRegion(50.0, 70.0, 150.0, 180.0, cqZone = 19, ituZone = 33))

        // 中东/中亚
        add(ZoneRegion(12.0, 42.0, 25.0, 63.0, cqZone = 21, ituZone = 39))
        add(ZoneRegion(24.0, 40.0, 34.0, 48.0, cqZone = 20, ituZone = 39))     // 中东
        add(ZoneRegion(38.0, 43.0, 26.0, 46.0, cqZone = 20, ituZone = 39))     // 土耳其
        add(ZoneRegion(24.0, 40.0, 43.0, 63.0, cqZone = 21, ituZone = 40))     // 伊朗/中亚

        // 非洲
        add(ZoneRegion(-35.0, 38.0, -17.0, 52.0, cqZone = 33, ituZone = 44))
        add(ZoneRegion(0.0, 38.0, -18.0, 12.0, cqZone = 33, ituZone = 46))     // 西北非
        add(ZoneRegion(4.0, 14.0, -4.0, 15.0, cqZone = 35, ituZone = 35))      // 几内亚湾沿岸（尼日利亚/加纳等）
        add(ZoneRegion(0.0, 18.0, 12.0, 32.0, cqZone = 37, ituZone = 47))      // 东非
        add(ZoneRegion(-35.0, 0.0, 12.0, 52.0, cqZone = 38, ituZone = 53))     // 南非/东南非
        // 中西非框北界收到 5°（原 6° 会以更靠后的添加顺序压过几内亚湾 CQ35 框）
        add(ZoneRegion(-35.0, 5.0, -18.0, 12.0, cqZone = 36, ituZone = 46))    // 中西非

        // 南亚
        add(ZoneRegion(6.0, 37.0, 68.0, 97.0, cqZone = 22, ituZone = 41))
        add(ZoneRegion(6.0, 37.0, 77.0, 90.0, cqZone = 22, ituZone = 41))      // 印度
        add(ZoneRegion(26.0, 37.0, 60.0, 78.0, cqZone = 21, ituZone = 40))     // 巴基斯坦/阿富汗

        // 东亚
        add(ZoneRegion(18.0, 54.0, 97.0, 122.0, cqZone = 24, ituZone = 44))    // 中国（大范围兜底）
        add(ZoneRegion(26.0, 40.0, 78.0, 97.0, cqZone = 23, ituZone = 42))    // 中国西部：西藏/新疆西部/青海
        add(ZoneRegion(20.0, 46.0, 122.0, 146.0, cqZone = 25, ituZone = 45))   // 日本/韩国
        add(ZoneRegion(35.0, 43.0, 128.0, 146.0, cqZone = 25, ituZone = 45))   // 日本

        // 东南亚/大洋洲
        add(ZoneRegion(-11.0, 20.0, 95.0, 142.0, cqZone = 26, ituZone = 49))
        add(ZoneRegion(-11.0, 7.0, 95.0, 120.0, cqZone = 28, ituZone = 54))    // 印尼/马来
        add(ZoneRegion(-11.0, 7.0, 120.0, 142.0, cqZone = 28, ituZone = 51))   // 印尼东部
        add(ZoneRegion(-44.0, -10.0, 112.0, 155.0, cqZone = 30, ituZone = 55)) // 澳大利亚
        add(ZoneRegion(-48.0, -34.0, 166.0, 179.0, cqZone = 32, ituZone = 60)) // 新西兰
        add(ZoneRegion(-10.0, -1.0, 140.0, 155.0, cqZone = 28, ituZone = 51))  // 巴布亚新几内亚
    }

    fun resolve(latitude: Double, longitude: Double): ZoneInfo {
        val maidenhead = MaidenheadCalculator.calculate(latitude, longitude)

        // 优先命中精确覆盖的区域
        for (region in regions.asReversed()) {
            if (region.contains(latitude, longitude)) {
                return ZoneInfo(region.cqZone, region.ituZone, maidenhead)
            }
        }

        // 兜底：基于经度的经验公式（海上/偏远地区）
        return ZoneInfo(
            cqZone = fallbackCqZone(longitude),
            ituZone = fallbackItuZone(longitude),
            maidenhead = maidenhead
        )
    }

    private fun fallbackCqZone(longitude: Double): Int {
        val normalized = longitude + 180.0
        val zone = floor(normalized / 9.0).toInt() + 1
        return zone.coerceIn(1, 40)
    }

    private fun fallbackItuZone(longitude: Double): Int {
        val normalized = longitude + 180.0
        val zone = floor(normalized / 4.0).toInt() + 1
        return zone.coerceIn(1, 90)
    }
}
