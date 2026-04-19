package com.dreamroute.monitor.task;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class VmRackStockMonitorTask {

    private static final Logger log = LoggerFactory.getLogger(VmRackStockMonitorTask.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient;
    private final JavaMailSender javaMailSender;
    private final Map<String, ProductStock> lastSnapshot = new LinkedHashMap<>();

    @Value("${vmrack.stock-monitor.enabled:true}")
    private boolean enabled;

    @Value("${vmrack.stock-monitor.url:https://www.vmrack.net/zh-CN/activity/2026-spring}")
    private String pageUrl;

    @Value("${vmrack.stock-monitor.target-section:三网精品云服务器}")
    private String targetSection;

    @Value("${vmrack.stock-monitor.target-name-prefix:L3.VPS}")
    private String targetNamePrefix;

    @Value("${vmrack.stock-monitor.request-timeout-ms:10000}")
    private long requestTimeoutMs;

    @Value("${vmrack.stock-monitor.alert-cooldown-ms:30000}")
    private long alertCooldownMs;

    @Value("${vmrack.stock-monitor.desktop-notification-enabled:true}")
    private boolean desktopNotificationEnabled;

    @Value("${vmrack.stock-monitor.open-browser-on-stock:true}")
    private boolean openBrowserOnStock;

    @Value("${vmrack.stock-monitor.webhook-url:}")
    private String webhookUrl;

    @Value("${vmrack.stock-monitor.email-enabled:true}")
    private boolean emailEnabled;

    @Value("${vmrack.stock-monitor.email-from:${spring.mail.username:}}")
    private String emailFrom;

    @Value("${vmrack.stock-monitor.email-to:342252328@qq.com}")
    private String emailTo;

    @Value("${vmrack.stock-monitor.test-alert-on-start:false}")
    private boolean testAlertOnStart;

    private Instant lastAlertAt = Instant.EPOCH;

    public VmRackStockMonitorTask(
            JavaMailSender javaMailSender,
            @Value("${vmrack.stock-monitor.connect-timeout-ms:5000}") long connectTimeoutMs) {
        this.javaMailSender = javaMailSender;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sendTestAlertOnStart() {
        if (!testAlertOnStart) {
            return;
        }

        ProductStock testProduct = new ProductStock("L3.VPS 模拟有货测试", "立即使用", "库存紧张", "测试价格", true);
        sendAlert("VMRack 三网精品有货测试", Collections.singletonList(testProduct));
    }

    @Scheduled(
            fixedDelayString = "${vmrack.stock-monitor.fixed-delay-ms:5000}",
            initialDelayString = "${vmrack.stock-monitor.initial-delay-ms:1000}")
    public void checkStock() {
        if (!enabled) {
            return;
        }

        try {
            List<ProductStock> products = fetchProductStocks();
            if (products.isEmpty()) {
                log.warn("VMRack stock monitor did not find products in section [{}] with prefix [{}]",
                        targetSection, targetNamePrefix);
                return;
            }

            logStateChanges(products);
            List<ProductStock> availableProducts = products.stream()
                    .filter(ProductStock::isAvailable)
                    .collect(Collectors.toList());

            if (!availableProducts.isEmpty() && shouldAlert(availableProducts)) {
                alert(availableProducts);
            }

            remember(products);
        } catch (Exception ex) {
            log.warn("VMRack stock monitor check failed: {}", ex.getMessage(), ex);
        }
    }

    private List<ProductStock> fetchProductStocks() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(pageUrl))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return parseProductStocks(response.body(), pageUrl, targetSection, targetNamePrefix);
    }

    static List<ProductStock> parseProductStocks(
            String html, String baseUri, String targetSection, String targetNamePrefix) {
        Document document = Jsoup.parse(html, baseUri);
        Element section = findSection(document, targetSection);
        if (section == null) {
            return Collections.emptyList();
        }

        List<ProductStock> products = new ArrayList<>();
        Elements cards = section.select(".activation-card-wrap");
        for (Element card : cards) {
            String name = text(card, ".package-name").trim();
            if (!StringUtils.hasText(name) || !name.startsWith(targetNamePrefix)) {
                continue;
            }

            String buttonText = text(card, "button.activation-button").trim();
            String badgeText = text(card, ".low-stock").trim();
            Element currentPrice = card.selectFirst(".cloud-num span");
            String price = currentPrice == null
                    ? text(card, ".cloud-num").replaceAll("\\s+", "")
                    : currentPrice.text().trim();
            boolean soldOut = containsAny(buttonText, "已售罄", "售罄", "Sold Out", "Sold out");
            boolean buyable = containsAny(buttonText, "立即使用", "购买", "Buy Now", "Buy", "Use");

            boolean available = buyable || (!soldOut && containsAny(badgeText, "库存紧张", "Low Stock"));

            products.add(new ProductStock(name, buttonText, badgeText, price, available));
        }
        return products;
    }

    private static Element findSection(Document document, String targetSection) {
        for (Element heading : document.select("h2.vps-type")) {
            if (heading.text().contains(targetSection)) {
                return heading.parent();
            }
        }
        return null;
    }

    private void logStateChanges(List<ProductStock> products) {
        for (ProductStock product : products) {
            ProductStock old = lastSnapshot.get(product.getName());
            if (old == null) {
                log.info("VMRack monitor: {} is currently {}", product.getName(), product.statusText());
            } else if (old.isAvailable() != product.isAvailable()
                    || !old.getButtonText().equals(product.getButtonText())) {
                log.info("VMRack monitor: {} changed from {} to {}",
                        product.getName(), old.statusText(), product.statusText());
            }
        }
    }

    private boolean shouldAlert(List<ProductStock> availableProducts) {
        boolean newlyAvailable = availableProducts.stream()
                .anyMatch(product -> {
                    ProductStock old = lastSnapshot.get(product.getName());
                    return old == null || !old.isAvailable();
                });
        boolean cooldownPassed = Duration.between(lastAlertAt, Instant.now()).toMillis() >= alertCooldownMs;
        return newlyAvailable || cooldownPassed;
    }

    private void alert(List<ProductStock> availableProducts) {
        lastAlertAt = Instant.now();
        sendAlert("VMRack 三网精品有货", availableProducts);
    }

    private void sendAlert(String title, List<ProductStock> availableProducts) {
        String productLine = availableProducts.stream()
                .map(product -> product.getName() + " " + product.getPrice() + " " + product.statusText())
                .collect(Collectors.joining("; "));
        String message = productLine + "，马上去抢购：" + pageUrl;

        log.warn("\u0007{} - {}", title, message);
        sendEmail(title, message, availableProducts);
        sendWebhook(title, message, availableProducts);
        sendDesktopNotification(title, message);
        openBrowser();
    }

    private void sendEmail(String title, String message, List<ProductStock> availableProducts) {
        if (!emailEnabled) {
            return;
        }
        if (!StringUtils.hasText(emailFrom) || !StringUtils.hasText(emailTo)) {
            log.warn("VMRack stock email skipped because email from/to is empty");
            return;
        }

        SimpleMailMessage mailMessage = new SimpleMailMessage();
        mailMessage.setFrom(emailFrom);
        mailMessage.setTo(emailTo.split("\\s*,\\s*"));
        mailMessage.setSubject(title);
        mailMessage.setText(buildEmailText(message, availableProducts));

        try {
            javaMailSender.send(mailMessage);
            log.info("VMRack stock email sent to {}", emailTo);
        } catch (Exception ex) {
            log.warn("VMRack stock email failed: {}", ex.getMessage(), ex);
        }
    }

    private String buildEmailText(String message, List<ProductStock> availableProducts) {
        StringBuilder content = new StringBuilder();
        content.append("VMRack 监控发现以下套餐可能可以购买：").append(System.lineSeparator())
                .append(System.lineSeparator());

        for (ProductStock product : availableProducts) {
            content.append("- ")
                    .append(product.getName())
                    .append("，价格：")
                    .append(StringUtils.hasText(product.getPrice()) ? product.getPrice() : "未知")
                    .append("，状态：")
                    .append(product.statusText())
                    .append(System.lineSeparator());
        }

        content.append(System.lineSeparator())
                .append("页面地址：").append(pageUrl).append(System.lineSeparator())
                .append("提醒时间：").append(LocalDateTime.now().format(DATE_TIME_FORMATTER)).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(message).append(System.lineSeparator());
        return content.toString();
    }

    private void sendWebhook(String title, String message, List<ProductStock> availableProducts) {
        if (!StringUtils.hasText(webhookUrl)) {
            return;
        }

        String productJson = availableProducts.stream()
                .map(product -> "{\"name\":\"" + jsonEscape(product.getName())
                        + "\",\"price\":\"" + jsonEscape(product.getPrice())
                        + "\",\"status\":\"" + jsonEscape(product.statusText()) + "\"}")
                .collect(Collectors.joining(","));
        String payload = "{\"title\":\"" + jsonEscape(title)
                + "\",\"message\":\"" + jsonEscape(message)
                + "\",\"url\":\"" + jsonEscape(pageUrl)
                + "\",\"products\":[" + productJson + "]}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                log.warn("VMRack stock webhook returned HTTP {}", response.statusCode());
            }
        } catch (Exception ex) {
            log.warn("VMRack stock webhook failed: {}", ex.getMessage());
        }
    }

    private void sendDesktopNotification(String title, String message) {
        if (!desktopNotificationEnabled || !isMac()) {
            return;
        }
        try {
            new ProcessBuilder("osascript", "-e",
                    "display notification \"" + appleScriptEscape(message)
                            + "\" with title \"" + appleScriptEscape(title)
                            + "\" sound name \"Glass\"")
                    .start();
        } catch (IOException ex) {
            log.warn("VMRack stock desktop notification failed: {}", ex.getMessage());
        }
    }

    private void openBrowser() {
        if (!openBrowserOnStock || !isMac()) {
            return;
        }
        try {
            new ProcessBuilder("open", pageUrl).start();
        } catch (IOException ex) {
            log.warn("VMRack stock browser open failed: {}", ex.getMessage());
        }
    }

    private void remember(List<ProductStock> products) {
        lastSnapshot.clear();
        for (ProductStock product : products) {
            lastSnapshot.put(product.getName(), product);
        }
    }

    private static String text(Element root, String cssQuery) {
        Element element = root.selectFirst(cssQuery);
        return element == null ? "" : element.text();
    }

    private static boolean containsAny(String value, String... needles) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lowerValue.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String appleScriptEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static class ProductStock {
        private final String name;
        private final String buttonText;
        private final String badgeText;
        private final String price;
        private final boolean available;

        ProductStock(String name, String buttonText, String badgeText, String price, boolean available) {
            this.name = name;
            this.buttonText = buttonText;
            this.badgeText = badgeText;
            this.price = price;
            this.available = available;
        }

        String getName() {
            return name;
        }

        String getButtonText() {
            return buttonText;
        }

        String getPrice() {
            return price;
        }

        boolean isAvailable() {
            return available;
        }

        String statusText() {
            if (available) {
                return StringUtils.hasText(badgeText) ? buttonText + "/" + badgeText : buttonText;
            }
            return StringUtils.hasText(buttonText) ? buttonText : "未知";
        }
    }
}
