package com.dreamroute.monitor.task;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class EvoxtPriceMonitorTask {

    private static final Logger log = LoggerFactory.getLogger(EvoxtPriceMonitorTask.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "\\$\\s*([0-9]+(?:\\.[0-9]+)?)\\s*/\\s*month",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSFER_PATTERN = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(TB|GB)",
            Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final JavaMailSender javaMailSender;
    private final Map<String, EvoxtPlanPrice> lastSnapshot = new LinkedHashMap<>();

    @Value("${evoxt.price-monitor.enabled:true}")
    private boolean enabled;

    @Value("${evoxt.price-monitor.url:https://evoxt.com/pricing}")
    private String url;

    @Value("${evoxt.price-monitor.series-name:Malaysia (Premium)}")
    private String seriesName;

    @Value("${evoxt.price-monitor.request-timeout-ms:15000}")
    private long requestTimeoutMs;

    @Value("${evoxt.price-monitor.email-enabled:true}")
    private boolean emailEnabled;

    @Value("${evoxt.price-monitor.email-from:${spring.mail.username:}}")
    private String emailFrom;

    @Value("${evoxt.price-monitor.email-to:342252328@qq.com}")
    private String emailTo;

    @Value("${evoxt.price-monitor.desktop-notification-enabled:true}")
    private boolean desktopNotificationEnabled;

    @Value("${evoxt.price-monitor.open-browser-on-price-drop:true}")
    private boolean openBrowserOnPriceDrop;

    public EvoxtPriceMonitorTask(
            JavaMailSender javaMailSender,
            @Value("${evoxt.price-monitor.connect-timeout-ms:5000}") long connectTimeoutMs) {
        this.javaMailSender = javaMailSender;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Scheduled(
            fixedDelayString = "${evoxt.price-monitor.fixed-delay-ms:60000}",
            initialDelayString = "${evoxt.price-monitor.initial-delay-ms:5000}")
    public void checkPrice() {
        if (!enabled) {
            return;
        }

        try {
            List<EvoxtPlanPrice> prices = parseSeriesPrices(fetchHtml(url), url, seriesName);
            if (prices.isEmpty()) {
                log.warn("Evoxt monitor: no {} prices parsed from {}", seriesName, url);
                return;
            }

            if (lastSnapshot.isEmpty()) {
                remember(prices);
                log.info("Evoxt monitor initialized: {} {} prices, cheapest {}", seriesName, prices.size(),
                        prices.stream()
                                .min((left, right) -> Double.compare(left.priceAmount, right.priceAmount))
                                .map(EvoxtPlanPrice::summary)
                                .orElse("unknown"));
                return;
            }

            List<PriceDrop> drops = findPriceDrops(lastSnapshot, prices);
            if (!drops.isEmpty()) {
                sendAlert(drops);
            }
            remember(prices);
        } catch (Exception ex) {
            log.warn("Evoxt price monitor failed: {}", ex.getMessage(), ex);
        }
    }

    private String fetchHtml(String pageUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(pageUrl))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " from " + pageUrl);
        }
        return response.body();
    }

    static List<EvoxtPlanPrice> parseSeriesPrices(String html, String sourceUrl, String seriesName) {
        Document document = Jsoup.parse(html, sourceUrl);
        Element heading = findSeriesHeading(document, seriesName);
        if (heading == null) {
            return new ArrayList<>();
        }

        Element table = findNextTable(heading);
        if (table == null) {
            return new ArrayList<>();
        }

        List<EvoxtPlanPrice> prices = new ArrayList<>();
        for (Element row : table.select("tr")) {
            Elements cells = row.select("td");
            if (cells.size() < 7) {
                continue;
            }
            PriceValue price = parsePrice(cells.get(6).text());
            if (price == null) {
                continue;
            }
            String monthlyTransfer = cells.get(4).text();
            prices.add(new EvoxtPlanPrice(
                    cells.get(0).text(),
                    cells.get(1).text(),
                    cells.get(2).text(),
                    cells.get(3).text(),
                    monthlyTransfer,
                    cells.get(5).text(),
                    parseTransferGb(monthlyTransfer),
                    price.amount,
                    price.text,
                    sourceUrl));
        }
        return prices;
    }

    static List<PriceDrop> findPriceDrops(Map<String, EvoxtPlanPrice> oldSnapshot,
                                          List<EvoxtPlanPrice> currentPrices) {
        if (oldSnapshot.isEmpty()) {
            return new ArrayList<>();
        }

        List<PriceDrop> drops = new ArrayList<>();
        for (EvoxtPlanPrice current : currentPrices) {
            EvoxtPlanPrice previous = oldSnapshot.get(current.uniqueKey());
            if (previous != null && current.isBetterThan(previous)) {
                drops.add(new PriceDrop(previous, current));
            }
        }
        return drops;
    }

    private void remember(List<EvoxtPlanPrice> prices) {
        lastSnapshot.clear();
        for (EvoxtPlanPrice price : prices) {
            lastSnapshot.put(price.uniqueKey(), price);
        }
    }

    private void sendAlert(List<PriceDrop> drops) {
        String title = "Evoxt Malaysia (Premium) 优惠变化提醒";
        String message = drops.stream()
                .map(PriceDrop::summary)
                .collect(Collectors.joining("; "));

        log.warn("\u0007{} - {}", title, message);
        sendEmail(title, buildEmailText(drops));
        sendDesktopNotification(title, message);
        openBrowser();
    }

    private void sendEmail(String title, String text) {
        if (!emailEnabled) {
            return;
        }
        if (!StringUtils.hasText(emailFrom) || !StringUtils.hasText(emailTo)) {
            log.warn("Evoxt price drop email skipped because email from/to is empty");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(emailFrom);
        message.setTo(emailTo.split("\\s*,\\s*"));
        message.setSubject(title);
        message.setText(text);

        try {
            javaMailSender.send(message);
            log.info("Evoxt price drop email sent to {}", emailTo);
        } catch (Exception ex) {
            log.warn("Evoxt price drop email failed: {}", ex.getMessage(), ex);
        }
    }

    private String buildEmailText(List<PriceDrop> drops) {
        StringBuilder content = new StringBuilder();
        content.append("Evoxt Malaysia (Premium) 系列发现优惠变化：")
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        for (PriceDrop drop : drops) {
            EvoxtPlanPrice current = drop.current;
            content.append("- ")
                    .append(current.planName)
                    .append("：")
                    .append(drop.reasonText())
                    .append(System.lineSeparator())
                    .append("  配置：")
                    .append(current.cpu)
                    .append("，")
                    .append(current.ram)
                    .append(" RAM，")
                    .append(current.storage)
                    .append(" SSD，")
                    .append(current.monthlyTransfer)
                    .append(" 流量，备份：")
                    .append(current.backup)
                    .append(System.lineSeparator())
                    .append("  页面：")
                    .append(current.sourceUrl)
                    .append(System.lineSeparator());
        }

        content.append(System.lineSeparator())
                .append("说明：价格下降，或者同价格下月流量增加，都会触发提醒。首次启动只记录基准价格，不会误报。")
                .append(System.lineSeparator())
                .append("提醒时间：")
                .append(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                .append(System.lineSeparator());
        return content.toString();
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
            log.warn("Evoxt price drop desktop notification failed: {}", ex.getMessage());
        }
    }

    private void openBrowser() {
        if (!openBrowserOnPriceDrop || !isMac()) {
            return;
        }
        try {
            new ProcessBuilder("open", url).start();
        } catch (IOException ex) {
            log.warn("Evoxt price page browser open failed for {}: {}", url, ex.getMessage());
        }
    }

    private static Element findSeriesHeading(Document document, String seriesName) {
        String expected = normalize(seriesName);
        for (Element element : document.select("h1,h2,h3,h4,h5,h6,p,div,span")) {
            if (Objects.equals(normalize(element.ownText()), expected)
                    || Objects.equals(normalize(element.text()), expected)) {
                return element;
            }
        }
        return null;
    }

    private static Element findNextTable(Element start) {
        Element current = start;
        while (current != null) {
            Element sibling = current.nextElementSibling();
            while (sibling != null) {
                Element table = "table".equalsIgnoreCase(sibling.tagName())
                        ? sibling
                        : sibling.selectFirst("table");
                if (table != null) {
                    return table;
                }
                sibling = sibling.nextElementSibling();
            }
            current = current.parent();
        }
        return null;
    }

    private static PriceValue parsePrice(String text) {
        Matcher matcher = PRICE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        double amount = Double.parseDouble(matcher.group(1));
        return new PriceValue(amount, "$" + matcher.group(1) + " / month");
    }

    private static double parseTransferGb(String text) {
        Matcher matcher = TRANSFER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        double amount = Double.parseDouble(matcher.group(1));
        if ("TB".equalsIgnoreCase(matcher.group(2))) {
            return amount * 1000;
        }
        return amount;
    }

    private static String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replace('\u00A0', ' ')
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String appleScriptEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class PriceValue {
        private final double amount;
        private final String text;

        private PriceValue(double amount, String text) {
            this.amount = amount;
            this.text = text;
        }
    }

    static final class EvoxtPlanPrice {
        private final String planName;
        private final String cpu;
        private final String ram;
        private final String storage;
        private final String monthlyTransfer;
        private final String backup;
        private final double monthlyTransferGb;
        private final double priceAmount;
        private final String priceText;
        private final String sourceUrl;

        EvoxtPlanPrice(String planName, String cpu, String ram, String storage, String monthlyTransfer,
                       String backup, double monthlyTransferGb, double priceAmount, String priceText,
                       String sourceUrl) {
            this.planName = planName;
            this.cpu = cpu;
            this.ram = ram;
            this.storage = storage;
            this.monthlyTransfer = monthlyTransfer;
            this.backup = backup;
            this.monthlyTransferGb = monthlyTransferGb;
            this.priceAmount = priceAmount;
            this.priceText = priceText;
            this.sourceUrl = sourceUrl;
        }

        String uniqueKey() {
            return planName;
        }

        String summary() {
            return planName + " " + priceText;
        }

        boolean isBetterThan(EvoxtPlanPrice previous) {
            if (priceAmount + 0.0001 < previous.priceAmount) {
                return true;
            }
            return Math.abs(priceAmount - previous.priceAmount) < 0.0001
                    && monthlyTransferGb > previous.monthlyTransferGb + 0.0001;
        }

        String getPlanName() {
            return planName;
        }

        double getPriceAmount() {
            return priceAmount;
        }

        String getPriceText() {
            return priceText;
        }

        String getMonthlyTransfer() {
            return monthlyTransfer;
        }
    }

    static final class PriceDrop {
        private final EvoxtPlanPrice previous;
        private final EvoxtPlanPrice current;

        PriceDrop(EvoxtPlanPrice previous, EvoxtPlanPrice current) {
            this.previous = previous;
            this.current = current;
        }

        String summary() {
            return current.planName + " " + reasonText();
        }

        String reasonText() {
            if (current.priceAmount + 0.0001 < previous.priceAmount) {
                return previous.priceText + " -> " + current.priceText;
            }
            return current.priceText + "，流量 " + previous.monthlyTransfer + " -> " + current.monthlyTransfer;
        }

        EvoxtPlanPrice getPrevious() {
            return previous;
        }

        EvoxtPlanPrice getCurrent() {
            return current;
        }
    }
}
