package com.dreamroute.monitor.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VmRackStockMonitorTaskTest {

    private static final String PREMIUM_KEYWORDS = "三网精品,CN2/9929/CMIN2,CN2 GIA,CMIN2,Premium";
    private static final String EXCLUDE_KEYWORDS = "三网优化,美国原生,Global BGP,Platinum,163/10099/CMI,Cogent/Arelion";
    private static final String SOLD_OUT_KEYWORDS = "已售罄,售罄,Sold Out,Sold out,活动结束,已结束,结束";
    private static final String BUYABLE_KEYWORDS = "库存紧张,低库存,购买,购买并部署,立即使用,Buy,Deploy,Low Stock";

    @Test
    void parseCheapServerOffersFindsBuyablePremiumDeployRow() {
        String html = "<html><body>"
                + "<h1>三网精品 VPS</h1>"
                + "<table><tbody>"
                + "<tr>"
                + "<td>库存紧张 L3.VPS.1C2G.Base</td>"
                + "<td>1 vCPU</td>"
                + "<td>2 GB 内存</td>"
                + "<td>1000GB/月 500Mbps</td>"
                + "<td><button>$7.99/月</button><span>$24.00/月</span></td>"
                + "</tr>"
                + "</tbody></table>"
                + "</body></html>";

        List<VmRackStockMonitorTask.CheapServerOffer> offers = VmRackStockMonitorTask.parseCheapServerOffers(
                html,
                "https://www.vmrack.net/zh-CN/deploy-new-instance/vps-hosting",
                PREMIUM_KEYWORDS,
                EXCLUDE_KEYWORDS,
                SOLD_OUT_KEYWORDS,
                BUYABLE_KEYWORDS,
                20,
                80);

        assertEquals(1, offers.size());
        assertEquals("L3.VPS.1C2G.Base", offers.get(0).getName());
        assertEquals("$7.99/月", offers.get(0).getPriceText());
        assertTrue(offers.get(0).isQualified());
    }

    @Test
    void parseCheapServerOffersSkipsSoldOutAndExcludedPlans() {
        String html = "<html><body>"
                + "<div class='activation-card-wrap'>"
                + "<h2>三网精品 L3.VPS.1C1G.Base</h2>"
                + "<span>$35.00/年</span>"
                + "<button>已售罄</button>"
                + "</div>"
                + "<div class='activation-card-wrap'>"
                + "<h2>三网优化 VPS.Special</h2>"
                + "<span>$6.99/月</span>"
                + "<button>立即使用</button>"
                + "</div>"
                + "</body></html>";

        List<VmRackStockMonitorTask.CheapServerOffer> offers = VmRackStockMonitorTask.parseCheapServerOffers(
                html,
                "https://www.vmrack.net/zh-CN/activity/2026-spring",
                PREMIUM_KEYWORDS,
                EXCLUDE_KEYWORDS,
                SOLD_OUT_KEYWORDS,
                BUYABLE_KEYWORDS,
                20,
                80);

        assertEquals(2, offers.size());
        assertFalse(offers.get(0).isQualified());
        assertFalse(offers.get(1).isQualified());
    }

    @Test
    void pricePeriodDetectsYearlyPrices() {
        assertEquals(VmRackStockMonitorTask.PricePeriod.YEAR, VmRackStockMonitorTask.PricePeriod.from("年"));
        assertEquals(VmRackStockMonitorTask.PricePeriod.YEAR, VmRackStockMonitorTask.PricePeriod.from("year"));
        assertEquals(VmRackStockMonitorTask.PricePeriod.MONTH, VmRackStockMonitorTask.PricePeriod.from("月"));
    }
}
