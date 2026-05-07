package com.dreamroute.monitor.task;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvoxtPriceMonitorTaskTest {

    @Test
    void parseMalaysiaPremiumPrices() {
        String html = "<html><body>"
                + "<p>Malaysia</p>"
                + "<div><table><tr><td>VM-0.5</td><td>1 core</td><td>512 MB</td><td>5 GB</td>"
                + "<td>500 GB</td><td>Weekly</td><td><strong>$2.99 / month</strong></td></tr></table></div>"
                + "<p>Malaysia (Premium)</p>"
                + "<div><table>"
                + "<tr><th>Plan</th><th>CPU</th><th>RAM</th><th>Storage</th>"
                + "<th>Monthly Transfer</th><th>Backup</th><th>Price</th></tr>"
                + "<tr><td>VM-0.5</td><td>1 core (Up to 6.0 GHz)</td><td>512 MB</td>"
                + "<td>5 GB</td><td>150 GB</td><td>Weekly</td><td><strong>$3.49 / month</strong></td></tr>"
                + "<tr><td>VM-1</td><td>1 core (Up to 6.0 GHz)</td><td>2 GB</td>"
                + "<td>20 GB</td><td>300 GB</td><td>Weekly</td><td><strong>$5.99 / month</strong></td></tr>"
                + "</table></div>"
                + "</body></html>";

        List<EvoxtPriceMonitorTask.EvoxtPlanPrice> prices = EvoxtPriceMonitorTask.parseSeriesPrices(
                html, "https://evoxt.com/pricing", "Malaysia (Premium)");

        assertEquals(2, prices.size());
        assertEquals("VM-0.5", prices.get(0).getPlanName());
        assertEquals(3.49, prices.get(0).getPriceAmount(), 0.0001);
        assertEquals("150 GB", prices.get(0).getMonthlyTransfer());
        assertEquals("VM-1", prices.get(1).getPlanName());
    }

    @Test
    void findPriceDropsDetectsLowerPrice() {
        Map<String, EvoxtPriceMonitorTask.EvoxtPlanPrice> oldSnapshot = new LinkedHashMap<>();
        oldSnapshot.put("VM-1", plan("VM-1", "300 GB", 5.99));

        List<EvoxtPriceMonitorTask.PriceDrop> drops = EvoxtPriceMonitorTask.findPriceDrops(
                oldSnapshot,
                List.of(plan("VM-1", "300 GB", 4.99)));

        assertEquals(1, drops.size());
        assertEquals("$5.99 / month -> $4.99 / month", drops.get(0).reasonText());
    }

    @Test
    void findPriceDropsDetectsSamePriceWithMoreTransfer() {
        Map<String, EvoxtPriceMonitorTask.EvoxtPlanPrice> oldSnapshot = new LinkedHashMap<>();
        oldSnapshot.put("VM-1", plan("VM-1", "300 GB", 5.99));

        List<EvoxtPriceMonitorTask.PriceDrop> drops = EvoxtPriceMonitorTask.findPriceDrops(
                oldSnapshot,
                List.of(plan("VM-1", "0.5 TB", 5.99)));

        assertEquals(1, drops.size());
        assertEquals("$5.99 / month，流量 300 GB -> 0.5 TB", drops.get(0).reasonText());
    }

    @Test
    void findPriceDropsIgnoresSamePriceAndSameTransfer() {
        Map<String, EvoxtPriceMonitorTask.EvoxtPlanPrice> oldSnapshot = new LinkedHashMap<>();
        oldSnapshot.put("VM-1", plan("VM-1", "300 GB", 5.99));

        List<EvoxtPriceMonitorTask.PriceDrop> drops = EvoxtPriceMonitorTask.findPriceDrops(
                oldSnapshot,
                List.of(plan("VM-1", "300 GB", 5.99)));

        assertTrue(drops.isEmpty());
    }

    private static EvoxtPriceMonitorTask.EvoxtPlanPrice plan(String name, String transfer, double price) {
        return new EvoxtPriceMonitorTask.EvoxtPlanPrice(
                name,
                "1 core",
                "2 GB",
                "20 GB",
                transfer,
                "Weekly",
                transferGb(transfer),
                price,
                "$" + price + " / month",
                "https://evoxt.com/pricing");
    }

    private static double transferGb(String transfer) {
        if (transfer.endsWith("TB")) {
            return Double.parseDouble(transfer.replace("TB", "").trim()) * 1000;
        }
        return Double.parseDouble(transfer.replace("GB", "").trim());
    }
}
