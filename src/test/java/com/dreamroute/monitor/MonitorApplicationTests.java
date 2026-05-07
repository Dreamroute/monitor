package com.dreamroute.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "vmrack.stock-monitor.enabled=false",
        "evoxt.price-monitor.enabled=false"
})
class MonitorApplicationTests {

    @Test
    void contextLoads() {
    }

}
