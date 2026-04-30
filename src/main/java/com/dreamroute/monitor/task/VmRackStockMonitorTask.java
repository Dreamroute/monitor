package com.dreamroute.monitor.task;

import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class VmRackStockMonitorTask {

    private static final Logger log = LoggerFactory.getLogger(VmRackStockMonitorTask.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "\\$\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:/\\s*)?(月|年|mo|mon|month|yr|year)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL_SERVER_NAME_PATTERN = Pattern.compile("L\\d+\\.VPS[.A-Za-z0-9_-]*");
    private static final Pattern GENERIC_SERVER_NAME_PATTERN = Pattern.compile("VPS[.A-Za-z0-9_-]*");
    private static final Pattern CPU_MEMORY_PLAN_PATTERN = Pattern.compile("\\b\\d+C\\d+G\\b", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final JavaMailSender javaMailSender;
    private final Map<String, CheapServerOffer> lastSnapshot = new LinkedHashMap<>();
    private final Map<String, ActivityCandidate> lastActivitySnapshot = new LinkedHashMap<>();

    @Value("${vmrack.stock-monitor.enabled:true}")
    private boolean enabled;

    @Value("${vmrack.stock-monitor.base-url:https://www.vmrack.net}")
    private String baseUrl;

    @Value("${vmrack.stock-monitor.seed-urls:https://www.vmrack.net/zh-CN,https://www.vmrack.net/zh-CN/vps,https://www.vmrack.net/zh-CN/vps/unmetered,https://www.vmrack.net/zh-CN/pricing,https://www.vmrack.net/zh-CN/deploy-new-instance/vps-hosting}")
    private String seedUrls;

    @Value("${vmrack.stock-monitor.max-pages-per-check:12}")
    private int maxPagesPerCheck;

    @Value("${vmrack.stock-monitor.render-wait-ms:6000}")
    private long renderWaitMs;

    @Value("${vmrack.stock-monitor.request-timeout-ms:15000}")
    private long requestTimeoutMs;

    @Value("${vmrack.stock-monitor.alert-cooldown-ms:1800000}")
    private long alertCooldownMs;

    @Value("${vmrack.stock-monitor.max-monthly-usd:20}")
    private double maxMonthlyUsd;

    @Value("${vmrack.stock-monitor.max-yearly-usd:80}")
    private double maxYearlyUsd;

    @Value("${vmrack.stock-monitor.premium-keywords:三网精品,CN2/9929/CMIN2,CN2 GIA,CMIN2,Premium}")
    private String premiumKeywords;

    @Value("${vmrack.stock-monitor.exclude-keywords:三网优化,美国原生,Global BGP,Platinum,163/10099/CMI,Cogent/Arelion}")
    private String excludeKeywords;

    @Value("${vmrack.stock-monitor.sold-out-keywords:已售罄,售罄,Sold Out,Sold out,活动结束,已结束,结束}")
    private String soldOutKeywords;

    @Value("${vmrack.stock-monitor.buyable-keywords:库存紧张,低库存,购买,购买并部署,立即使用,Buy,Deploy,Low Stock}")
    private String buyableKeywords;

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

    @Value("${vmrack.stock-monitor.health-alert-enabled:true}")
    private boolean healthAlertEnabled;

    @Value("${vmrack.stock-monitor.health-empty-scan-threshold:5}")
    private int healthEmptyScanThreshold;

    @Value("${vmrack.stock-monitor.health-page-failure-threshold:3}")
    private int healthPageFailureThreshold;

    @Value("${vmrack.stock-monitor.health-broken-scan-threshold:3}")
    private int healthBrokenScanThreshold;

    @Value("${vmrack.stock-monitor.health-alert-cooldown-ms:21600000}")
    private long healthAlertCooldownMs;

    @Value("${vmrack.stock-monitor.activity-alert-enabled:true}")
    private boolean activityAlertEnabled;

    @Value("${vmrack.stock-monitor.sitemap-urls:https://www.vmrack.net/sitemap.xml,https://www.vmrack.net/sitemap_index.xml}")
    private String sitemapUrls;

    @Value("${vmrack.stock-monitor.activity-path-keywords:/activity/,/promo,/campaign,/deals,/sale}")
    private String activityPathKeywords;

    @Value("${vmrack.stock-monitor.activity-keywords:活动,优惠,促销,特惠,限时,限量,闪购,补货,春季,夏季,秋季,冬季,新年,黑五,双十一,Promo,Campaign,Sale,Special,Deal}")
    private String activityKeywords;

    @Value("${vmrack.stock-monitor.activity-ended-keywords:活动结束,已结束,结束,售罄,已售罄,Sold Out,Sold out,Expired,Ended}")
    private String activityEndedKeywords;

    @Value("${vmrack.stock-monitor.activity-alert-cooldown-ms:21600000}")
    private long activityAlertCooldownMs;

    private Instant lastAlertAt = Instant.EPOCH;
    private Instant lastHealthAlertAt = Instant.EPOCH;
    private Instant lastActivityAlertAt = Instant.EPOCH;
    private int consecutiveEmptyScanCount;
    private int consecutiveBrokenScanCount;

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

        CheapServerOffer testOffer = new CheapServerOffer(
                "VMRack 三网精品模拟低价套餐",
                "$7.99/月",
                7.99,
                PricePeriod.MONTH,
                "库存紧张",
                "https://www.vmrack.net/zh-CN",
                "三网精品 CN2/9929/CMIN2 低价测试套餐",
                true);
        sendAlert("VMRack 低价三网精品服务器测试", Collections.singletonList(testOffer));
    }

    @Scheduled(
            fixedDelayString = "${vmrack.stock-monitor.fixed-delay-ms:5000}",
            initialDelayString = "${vmrack.stock-monitor.initial-delay-ms:5000}")
    public void checkStock() {
        if (!enabled) {
            return;
        }

        try {
            ScanResult result = scanSite();
            List<CheapServerOffer> offers = result.offers;
            logStateChanges(offers);
            logActivityStateChanges(result.activities);

            List<ActivityCandidate> newActivities = result.activities.stream()
                    .filter(ActivityCandidate::isActive)
                    .filter(this::shouldAlertActivity)
                    .collect(Collectors.toList());
            if (!newActivities.isEmpty()) {
                lastActivityAlertAt = Instant.now();
                sendActivityAlert(newActivities);
            }

            List<CheapServerOffer> qualifiedOffers = offers.stream()
                    .filter(CheapServerOffer::isQualified)
                    .collect(Collectors.toList());
            if (!qualifiedOffers.isEmpty() && shouldAlert(qualifiedOffers)) {
                lastAlertAt = Instant.now();
                sendAlert("VMRack 发现低价三网精品服务器", qualifiedOffers);
            }

            remember(offers);
            rememberActivities(result.activities);
        } catch (Exception ex) {
            log.warn("VMRack cheap server monitor failed: {}", ex.getMessage(), ex);
        }
    }

    ScanResult scanSite() {
        Set<String> urlsToVisit = configuredSeedUrls();
        urlsToVisit.addAll(discoverSitemapUrls());
        Set<String> visited = new LinkedHashSet<>();
        List<CheapServerOffer> offers = new ArrayList<>();
        List<ActivityCandidate> activities = new ArrayList<>();
        int failedPageCount = 0;
        int healthyPageCount = 0;
        int offerSignalPageCount = 0;

        while (!urlsToVisit.isEmpty() && visited.size() < maxPagesPerCheck) {
            String url = urlsToVisit.iterator().next();
            urlsToVisit.remove(url);
            if (!visited.add(url)) {
                continue;
            }

            try {
                RenderedPage page = renderPage(url);
                if (pageLooksHealthy(page)) {
                    healthyPageCount++;
                }
                if (pageHasOfferSignals(page)) {
                    offerSignalPageCount++;
                }
                offers.addAll(extractOffers(page));
                ActivityCandidate activity = extractActivityCandidate(page);
                if (activity != null) {
                    activities.add(activity);
                }
                collectInterestingLinks(page.document).stream()
                        .filter(link -> !visited.contains(link))
                        .forEach(urlsToVisit::add);
            } catch (Exception ex) {
                failedPageCount++;
                log.warn("VMRack page scan failed for {}: {}", url, ex.getMessage());
            }
        }

        List<CheapServerOffer> dedupedOffers = dedupeOffers(offers);
        updateMonitorHealth(dedupedOffers, visited.size(), failedPageCount, healthyPageCount, offerSignalPageCount);
        return new ScanResult(dedupedOffers, dedupeActivities(activities));
    }

    private void updateMonitorHealth(List<CheapServerOffer> offers, int visitedPageCount, int failedPageCount,
                                     int healthyPageCount, int offerSignalPageCount) {
        if (!offers.isEmpty() || offerSignalPageCount == 0) {
            consecutiveEmptyScanCount = 0;
        } else {
            consecutiveEmptyScanCount++;
        }

        if (visitedPageCount == 0 || healthyPageCount == 0 || failedPageCount >= healthPageFailureThreshold) {
            consecutiveBrokenScanCount++;
        } else {
            consecutiveBrokenScanCount = 0;
        }

        if (shouldHealthAlert()) {
            lastHealthAlertAt = Instant.now();
            sendHealthAlert(visitedPageCount, failedPageCount, healthyPageCount, offerSignalPageCount);
        }
    }

    private boolean shouldHealthAlert() {
        if (!healthAlertEnabled) {
            return false;
        }
        boolean suspiciousEmpty = consecutiveEmptyScanCount >= healthEmptyScanThreshold;
        boolean suspiciousFailures = consecutiveBrokenScanCount >= healthBrokenScanThreshold;
        boolean cooldownPassed = Duration.between(lastHealthAlertAt, Instant.now()).toMillis() >= healthAlertCooldownMs;
        return (suspiciousEmpty || suspiciousFailures) && cooldownPassed;
    }

    private RenderedPage renderPage(String url) throws IOException, InterruptedException {
        try (WebClient webClient = new WebClient(BrowserVersion.CHROME)) {
            webClient.getOptions().setJavaScriptEnabled(true);
            webClient.getOptions().setCssEnabled(false);
            webClient.getOptions().setThrowExceptionOnScriptError(false);
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webClient.getOptions().setTimeout((int) requestTimeoutMs);
            webClient.addRequestHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            webClient.addRequestHeader("User-Agent", USER_AGENT);

            HtmlPage page = webClient.getPage(url);
            webClient.waitForBackgroundJavaScriptStartingBefore(renderWaitMs);
            webClient.waitForBackgroundJavaScript(renderWaitMs);
            Document document = Jsoup.parse(page.asXml(), url);
            return new RenderedPage(url, document);
        } catch (Exception ex) {
            log.debug("VMRack HtmlUnit render failed for {}, falling back to static fetch: {}", url, ex.getMessage());
            return fetchStaticPage(url);
        }
    }

    private RenderedPage fetchStaticPage(String url) throws IOException, InterruptedException {
        return fetchStaticPage(url, requestTimeoutMs);
    }

    private RenderedPage fetchStaticPage(String url, long timeoutMs) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(timeoutMs))
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
        return new RenderedPage(response.uri().toString(), Jsoup.parse(response.body(), response.uri().toString()));
    }

    private Set<String> discoverSitemapUrls() {
        Set<String> urls = new LinkedHashSet<>();
        for (String sitemapUrl : csvToList(sitemapUrls)) {
            try {
                collectSitemapPageUrls(sitemapUrl, urls, 0);
            } catch (Exception ex) {
                log.debug("VMRack sitemap scan failed for {}: {}", sitemapUrl, ex.getMessage());
            }
        }
        return urls;
    }

    private void collectSitemapPageUrls(String sitemapUrl, Set<String> urls, int depth)
            throws IOException, InterruptedException {
        if (depth > 1) {
            return;
        }
        for (String url : fetchSitemapUrls(sitemapUrl)) {
            String normalizedUrl = normalizeSameSiteUrl(url);
            if (StringUtils.hasText(normalizedUrl) && isInterestingPath(URI.create(normalizedUrl).getPath())) {
                urls.add(normalizedUrl);
                continue;
            }
            if (isSameSiteSitemapUrl(url)) {
                collectSitemapPageUrls(url, urls, depth + 1);
            }
        }
    }

    private Set<String> fetchSitemapUrls(String sitemapUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(sitemapUrl))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/xml,text/xml,text/plain,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }

        Set<String> urls = new LinkedHashSet<>();
        Document document = Jsoup.parse(response.body(), response.uri().toString(), Parser.xmlParser());
        for (Element loc : document.select("loc")) {
            String url = normalize(loc.text());
            if (StringUtils.hasText(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private List<CheapServerOffer> extractOffers(RenderedPage page) {
        return extractOffers(
                page,
                premiumKeywordList(),
                csvToList(excludeKeywords),
                csvToList(soldOutKeywords),
                csvToList(buyableKeywords),
                maxMonthlyUsd,
                maxYearlyUsd);
    }

    static List<CheapServerOffer> parseCheapServerOffers(
            String html,
            String baseUri,
            String premiumKeywords,
            String excludeKeywords,
            String soldOutKeywords,
            String buyableKeywords,
            double maxMonthlyUsd,
            double maxYearlyUsd) {
        return extractOffers(
                new RenderedPage(baseUri, Jsoup.parse(html, baseUri)),
                csvToList(premiumKeywords),
                csvToList(excludeKeywords),
                csvToList(soldOutKeywords),
                csvToList(buyableKeywords),
                maxMonthlyUsd,
                maxYearlyUsd);
    }

    private static List<CheapServerOffer> extractOffers(
            RenderedPage page,
            List<String> premiumKeywords,
            List<String> excludeKeywords,
            List<String> soldOutKeywords,
            List<String> buyableKeywords,
            double maxMonthlyUsd,
            double maxYearlyUsd) {
        List<CheapServerOffer> offers = new ArrayList<>();
        String searchableText = searchableText(page.document);
        boolean pagePremium = containsAnyToken(searchableText, premiumKeywords);

        offers.addAll(extractTableOffers(
                page, pagePremium, premiumKeywords, excludeKeywords, soldOutKeywords,
                buyableKeywords, maxMonthlyUsd, maxYearlyUsd));
        offers.addAll(extractCardOffers(
                page, pagePremium, premiumKeywords, excludeKeywords, soldOutKeywords,
                buyableKeywords, maxMonthlyUsd, maxYearlyUsd));
        offers.addAll(extractTextWindowOffers(
                page, searchableText, pagePremium, premiumKeywords, excludeKeywords, soldOutKeywords,
                buyableKeywords, maxMonthlyUsd, maxYearlyUsd));

        List<CheapServerOffer> parsedOffers = offers.stream()
                .filter(Objects::nonNull)
                .filter(CheapServerOffer::hasPrice)
                .collect(Collectors.toList());
        return dedupeOffers(parsedOffers);
    }

    private static List<CheapServerOffer> extractTableOffers(
            RenderedPage page,
            boolean pagePremium,
            List<String> premiumKeywords,
            List<String> excludeKeywords,
            List<String> soldOutKeywords,
            List<String> buyableKeywords,
            double maxMonthlyUsd,
            double maxYearlyUsd) {
        List<CheapServerOffer> offers = new ArrayList<>();
        for (Element row : page.document.select("tr")) {
            String rowText = normalize(row.text());
            if (!looksLikeServerOffer(rowText) || !hasPrice(rowText)) {
                continue;
            }
            boolean premium = isPremium(rowText, premiumKeywords) || pageSuggestsPremium(page.url, pagePremium);
            offers.add(buildOffer(
                    page.url, rowText, premium, excludeKeywords, soldOutKeywords,
                    buyableKeywords, maxMonthlyUsd, maxYearlyUsd));
        }
        return offers;
    }

    private static List<CheapServerOffer> extractCardOffers(
            RenderedPage page,
            boolean pagePremium,
            List<String> premiumKeywords,
            List<String> excludeKeywords,
            List<String> soldOutKeywords,
            List<String> buyableKeywords,
            double maxMonthlyUsd,
            double maxYearlyUsd) {
        List<CheapServerOffer> offers = new ArrayList<>();
        String selector = ".activation-card-wrap, article, li, [class*=card], [class*=Card], [class*=plan], [class*=Plan]";
        for (Element element : page.document.select(selector)) {
            String text = normalize(element.text());
            if (text.length() < 12 || text.length() > 800 || !looksLikeServerOffer(text) || !hasPrice(text)) {
                continue;
            }
            boolean premium = isPremium(text, premiumKeywords) || pageSuggestsPremium(page.url, pagePremium);
            offers.add(buildOffer(
                    page.url, text, premium, excludeKeywords, soldOutKeywords,
                    buyableKeywords, maxMonthlyUsd, maxYearlyUsd));
        }
        return offers;
    }

    private static List<CheapServerOffer> extractTextWindowOffers(
            RenderedPage page,
            String searchableText,
            boolean pagePremium,
            List<String> premiumKeywords,
            List<String> excludeKeywords,
            List<String> soldOutKeywords,
            List<String> buyableKeywords,
            double maxMonthlyUsd,
            double maxYearlyUsd) {
        String text = normalize(searchableText);
        if (!pagePremium || !hasPositivePrice(text)) {
            return Collections.emptyList();
        }

        List<CheapServerOffer> offers = new ArrayList<>();
        Matcher matcher = PRICE_PATTERN.matcher(text);
        while (matcher.find()) {
            int start = Math.max(0, matcher.start() - 220);
            int end = Math.min(text.length(), matcher.end() + 220);
            String context = text.substring(start, end);
            if (looksLikeConcreteServerOffer(context) && isPremium(context, premiumKeywords)) {
                offers.add(buildOffer(
                        page.url, context, true, excludeKeywords, soldOutKeywords,
                        buyableKeywords, maxMonthlyUsd, maxYearlyUsd));
            }
        }
        return offers;
    }

    private static CheapServerOffer buildOffer(
            String url,
            String context,
            boolean premium,
            List<String> excludeKeywords,
            List<String> soldOutKeywords,
            List<String> buyableKeywords,
            double maxMonthlyUsd,
            double maxYearlyUsd) {
        Price price = firstPositivePrice(context);
        if (price == null) {
            return null;
        }

        String name = extractName(context);
        boolean excluded = containsAnyToken(context, excludeKeywords);
        boolean soldOut = containsAnyToken(context, soldOutKeywords);
        boolean buyable = !soldOut && (containsAnyToken(context, buyableKeywords) || looksLikeDeployPage(url));
        boolean cheap = price.isCheap(maxMonthlyUsd, maxYearlyUsd);
        boolean qualified = premium && !excluded && buyable && cheap;
        String status = soldOut ? "已售罄/已结束" : (buyable ? "可能可购买" : "待确认");

        return new CheapServerOffer(
                name,
                price.text,
                price.amount,
                price.period,
                status,
                url,
                context,
                qualified);
    }

    private ActivityCandidate extractActivityCandidate(RenderedPage page) {
        String text = searchableText(page.document);
        boolean activityPath = isActivityPath(page.url);
        boolean activityText = containsAnyToken(text, csvToList(activityKeywords));
        boolean active = ((activityPath && pageLooksHealthy(page)
                && (activityText || hasPositivePrice(text) || pageHasOfferSignals(page)))
                || (activityText && pageHasOfferSignals(page)))
                && !containsAnyToken(text, csvToList(activityEndedKeywords));
        if (!activityPath && !active) {
            return null;
        }

        return new ActivityCandidate(
                pageTitle(page.document),
                page.url,
                active ? "疑似新活动/优惠进行中" : "活动页待确认",
                shorten(text, 360),
                active);
    }

    private Set<String> collectInterestingLinks(Document document) {
        Set<String> links = new LinkedHashSet<>();
        for (Element link : document.select("a[href]")) {
            String absUrl = link.absUrl("href");
            String normalizedUrl = normalizeSameSiteUrl(absUrl);
            if (!StringUtils.hasText(normalizedUrl)) {
                continue;
            }
            if (isInterestingPath(URI.create(normalizedUrl).getPath())) {
                links.add(normalizedUrl);
            }
        }
        return links;
    }

    private boolean isInterestingPath(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        return value.contains("vps")
                || value.contains("pricing")
                || value.contains("activity")
                || isActivityPath(path)
                || value.contains("deploy-new-instance");
    }

    private void logStateChanges(List<CheapServerOffer> offers) {
        for (CheapServerOffer offer : offers) {
            CheapServerOffer old = lastSnapshot.get(offer.uniqueKey());
            if (old == null) {
                logOffer(offer);
            } else if (old.qualified != offer.qualified || !old.status.equals(offer.status)) {
                log.info("VMRack monitor: {} changed from {} to {} [{}]",
                        offer.name, old.status, offer.status, offer.sourceUrl);
            }
        }
    }

    private void logOffer(CheapServerOffer offer) {
        if (offer.qualified || "可能可购买".equals(offer.status)) {
            log.info("VMRack monitor: {} {} {} [{}]",
                    offer.name, offer.priceText, offer.status, offer.sourceUrl);
            return;
        }
        log.debug("VMRack monitor candidate: {} {} {} [{}]",
                offer.name, offer.priceText, offer.status, offer.sourceUrl);
    }

    private void logActivityStateChanges(List<ActivityCandidate> activities) {
        for (ActivityCandidate activity : activities) {
            ActivityCandidate old = lastActivitySnapshot.get(activity.uniqueKey());
            if (old == null) {
                log.info("VMRack activity monitor: {} {} [{}]",
                        activity.title, activity.status, activity.sourceUrl);
            } else if (old.active != activity.active || !old.status.equals(activity.status)) {
                log.info("VMRack activity monitor: {} changed from {} to {} [{}]",
                        activity.title, old.status, activity.status, activity.sourceUrl);
            }
        }
    }

    private boolean shouldAlert(List<CheapServerOffer> offers) {
        boolean hasNewQualifiedOffer = offers.stream()
                .anyMatch(offer -> {
                    CheapServerOffer old = lastSnapshot.get(offer.uniqueKey());
                    return old == null || !old.qualified;
                });
        boolean cooldownPassed = Duration.between(lastAlertAt, Instant.now()).toMillis() >= alertCooldownMs;
        return hasNewQualifiedOffer || cooldownPassed;
    }

    private boolean shouldAlertActivity(ActivityCandidate activity) {
        if (!activityAlertEnabled) {
            return false;
        }
        ActivityCandidate old = lastActivitySnapshot.get(activity.uniqueKey());
        boolean newlyActive = old == null || !old.active;
        boolean cooldownPassed = Duration.between(lastActivityAlertAt, Instant.now()).toMillis()
                >= activityAlertCooldownMs;
        return newlyActive || cooldownPassed;
    }

    private void remember(List<CheapServerOffer> offers) {
        lastSnapshot.clear();
        for (CheapServerOffer offer : offers) {
            lastSnapshot.put(offer.uniqueKey(), offer);
        }
    }

    private void rememberActivities(List<ActivityCandidate> activities) {
        lastActivitySnapshot.clear();
        for (ActivityCandidate activity : activities) {
            lastActivitySnapshot.put(activity.uniqueKey(), activity);
        }
    }

    private void sendAlert(String title, List<CheapServerOffer> offers) {
        String message = offers.stream()
                .map(offer -> offer.name + " " + offer.priceText + " " + offer.status + " " + offer.sourceUrl)
                .collect(Collectors.joining("; "));

        log.warn("\u0007{} - {}", title, message);
        sendEmail(title, offers);
        sendWebhook(title, message, offers);
        sendDesktopNotification(title, message);
        openBrowser(offers);
    }

    private void sendEmail(String title, List<CheapServerOffer> offers) {
        sendTextEmail(title, buildEmailText(offers), "cheap server");
    }

    private void sendActivityAlert(List<ActivityCandidate> activities) {
        String title = "VMRack 发现新活动页面";
        String message = activities.stream()
                .map(activity -> activity.title + " " + activity.status + " " + activity.sourceUrl)
                .collect(Collectors.joining("; "));

        log.warn("\u0007{} - {}", title, message);
        sendTextEmail(title, buildActivityEmailText(activities), "activity");
        sendDesktopNotification(title, message);
        openActivityPages(activities);
    }

    private void sendTextEmail(String title, String text, String alertType) {
        if (!emailEnabled) {
            return;
        }
        if (!StringUtils.hasText(emailFrom) || !StringUtils.hasText(emailTo)) {
            log.warn("VMRack {} email skipped because email from/to is empty", alertType);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(emailFrom);
        message.setTo(emailTo.split("\\s*,\\s*"));
        message.setSubject(title);
        message.setText(text);

        try {
            javaMailSender.send(message);
            log.info("VMRack {} email sent to {}", alertType, emailTo);
        } catch (Exception ex) {
            log.warn("VMRack {} email failed: {}", alertType, ex.getMessage(), ex);
        }
    }

    private void sendHealthAlert(int visitedPageCount, int failedPageCount,
                                 int healthyPageCount, int offerSignalPageCount) {
        String title = "VMRack 监控可能失效";
        String text = buildHealthAlertText(visitedPageCount, failedPageCount, healthyPageCount, offerSignalPageCount);

        log.warn("\u0007{} - consecutiveEmptyScanCount={}, consecutiveBrokenScanCount={}, visitedPageCount={}, "
                        + "failedPageCount={}, healthyPageCount={}, offerSignalPageCount={}",
                title, consecutiveEmptyScanCount, consecutiveBrokenScanCount, visitedPageCount, failedPageCount,
                healthyPageCount, offerSignalPageCount);
        sendTextEmail(title, text, "monitor health");
        sendDesktopNotification(title, "连续多轮没有解析到 VMRack 套餐，可能网站结构变了，需要检查监控代码。");
    }

    private String buildHealthAlertText(int visitedPageCount, int failedPageCount,
                                        int healthyPageCount, int offerSignalPageCount) {
        return "VMRack 监控连续多轮出现异常，可能是网站结构大改、Cloudflare 拦截，或页面渲染/解析规则失效。"
                + System.lineSeparator()
                + System.lineSeparator()
                + "连续空结果次数：" + consecutiveEmptyScanCount + System.lineSeparator()
                + "连续抓取异常次数：" + consecutiveBrokenScanCount + System.lineSeparator()
                + "本轮访问页面数：" + visitedPageCount + System.lineSeparator()
                + "本轮失败页面数：" + failedPageCount + System.lineSeparator()
                + "本轮正常 VMRack 页面数：" + healthyPageCount + System.lineSeparator()
                + "本轮疑似套餐/价格页面数：" + offerSignalPageCount + System.lineSeparator()
                + "空结果告警阈值：" + healthEmptyScanThreshold + System.lineSeparator()
                + "单轮页面失败阈值：" + healthPageFailureThreshold + System.lineSeparator()
                + "连续失败告警阈值：" + healthBrokenScanThreshold + System.lineSeparator()
                + "种子页面：" + seedUrls + System.lineSeparator()
                + "提醒时间：" + LocalDateTime.now().format(DATE_TIME_FORMATTER) + System.lineSeparator()
                + System.lineSeparator()
                + "建议：登录服务器查看日志中的 'VMRack page scan failed'，然后让 Codex 根据最新页面改解析逻辑。";
    }

    private String buildEmailText(List<CheapServerOffer> offers) {
        StringBuilder content = new StringBuilder();
        content.append("VMRack 监控发现低价三网精品服务器：").append(System.lineSeparator())
                .append(System.lineSeparator());

        for (CheapServerOffer offer : offers) {
            content.append("- ")
                    .append(offer.name)
                    .append("，价格：")
                    .append(offer.priceText)
                    .append("，状态：")
                    .append(offer.status)
                    .append(System.lineSeparator())
                    .append("  页面：")
                    .append(offer.sourceUrl)
                    .append(System.lineSeparator())
                    .append("  片段：")
                    .append(shorten(offer.context, 260))
                    .append(System.lineSeparator());
        }

        content.append(System.lineSeparator())
                .append("阈值：月付 <= $").append(maxMonthlyUsd)
                .append("，年付 <= $").append(maxYearlyUsd).append(System.lineSeparator())
                .append("提醒时间：").append(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                .append(System.lineSeparator());
        return content.toString();
    }

    private String buildActivityEmailText(List<ActivityCandidate> activities) {
        StringBuilder content = new StringBuilder();
        content.append("VMRack 监控发现疑似新活动/优惠页面：").append(System.lineSeparator())
                .append(System.lineSeparator());

        for (ActivityCandidate activity : activities) {
            content.append("- ")
                    .append(activity.title)
                    .append("，状态：")
                    .append(activity.status)
                    .append(System.lineSeparator())
                    .append("  页面：")
                    .append(activity.sourceUrl)
                    .append(System.lineSeparator())
                    .append("  片段：")
                    .append(shorten(activity.context, 260))
                    .append(System.lineSeparator());
        }

        content.append(System.lineSeparator())
                .append("说明：这是活动入口提醒；如果页面里同时出现符合阈值的低价三网精品套餐，会再发送购买提醒。")
                .append(System.lineSeparator())
                .append("提醒时间：").append(LocalDateTime.now().format(DATE_TIME_FORMATTER))
                .append(System.lineSeparator());
        return content.toString();
    }

    private void sendWebhook(String title, String message, List<CheapServerOffer> offers) {
        if (!StringUtils.hasText(webhookUrl)) {
            return;
        }

        String productJson = offers.stream()
                .map(offer -> "{\"name\":\"" + jsonEscape(offer.name)
                        + "\",\"price\":\"" + jsonEscape(offer.priceText)
                        + "\",\"status\":\"" + jsonEscape(offer.status)
                        + "\",\"url\":\"" + jsonEscape(offer.sourceUrl) + "\"}")
                .collect(Collectors.joining(","));
        String payload = "{\"title\":\"" + jsonEscape(title)
                + "\",\"message\":\"" + jsonEscape(message)
                + "\",\"url\":\"" + jsonEscape(offers.isEmpty() ? baseUrl : offers.get(0).sourceUrl)
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
                log.warn("VMRack cheap server webhook returned HTTP {}", response.statusCode());
            }
        } catch (Exception ex) {
            log.warn("VMRack cheap server webhook failed: {}", ex.getMessage());
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
            log.warn("VMRack cheap server desktop notification failed: {}", ex.getMessage());
        }
    }

    private void openBrowser(List<CheapServerOffer> offers) {
        if (!openBrowserOnStock || !isMac()) {
            return;
        }
        Set<String> urls = offers.stream()
                .map(offer -> offer.sourceUrl)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String url : urls) {
            try {
                new ProcessBuilder("open", url).start();
            } catch (IOException ex) {
                log.warn("VMRack cheap server browser open failed for {}: {}", url, ex.getMessage());
            }
        }
    }

    private void openActivityPages(List<ActivityCandidate> activities) {
        if (!openBrowserOnStock || !isMac()) {
            return;
        }
        Set<String> urls = activities.stream()
                .map(activity -> activity.sourceUrl)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String url : urls) {
            try {
                new ProcessBuilder("open", url).start();
            } catch (IOException ex) {
                log.warn("VMRack activity browser open failed for {}: {}", url, ex.getMessage());
            }
        }
    }

    private static List<CheapServerOffer> dedupeOffers(List<CheapServerOffer> offers) {
        Map<String, CheapServerOffer> deduped = new LinkedHashMap<>();
        for (CheapServerOffer offer : offers) {
            CheapServerOffer existing = deduped.get(offer.uniqueKey());
            if (existing == null || (!existing.qualified && offer.qualified)) {
                deduped.put(offer.uniqueKey(), offer);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private static List<ActivityCandidate> dedupeActivities(List<ActivityCandidate> activities) {
        Map<String, ActivityCandidate> deduped = new LinkedHashMap<>();
        for (ActivityCandidate activity : activities) {
            ActivityCandidate existing = deduped.get(activity.uniqueKey());
            if (existing == null || (!existing.active && activity.active)) {
                deduped.put(activity.uniqueKey(), activity);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private Set<String> configuredSeedUrls() {
        return csvToList(seedUrls).stream()
                .map(String::trim)
                .map(this::normalizeSameSiteUrl)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> premiumKeywordList() {
        return csvToList(premiumKeywords);
    }

    private boolean isSameSiteSitemapUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        URI base = URI.create(baseUrl);
        URI uri;
        try {
            uri = URI.create(url.split("#")[0]);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (!uri.isAbsolute()) {
            uri = base.resolve(uri);
        }
        if (uri.getHost() == null || !base.getHost().equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        String path = uri.getPath();
        return path != null && path.toLowerCase(Locale.ROOT).contains("sitemap");
    }

    private String normalizeSameSiteUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        URI base = URI.create(baseUrl);
        URI uri;
        try {
            uri = URI.create(url.split("#")[0]);
        } catch (IllegalArgumentException ex) {
            return "";
        }
        if (!uri.isAbsolute()) {
            uri = base.resolve(uri);
        }
        if (uri.getHost() == null || !base.getHost().equalsIgnoreCase(uri.getHost())) {
            return "";
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/zh-CN")) {
            return "";
        }
        return uri.toString();
    }

    private boolean isActivityPath(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String lowerValue = value.toLowerCase(Locale.ROOT);
        for (String keyword : csvToList(activityPathKeywords)) {
            if (lowerValue.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String pageTitle(Document document) {
        String title = normalize(document.title());
        if (StringUtils.hasText(title)) {
            return title;
        }
        Element h1 = document.selectFirst("h1");
        if (h1 != null && StringUtils.hasText(h1.text())) {
            return normalize(h1.text());
        }
        return "VMRack 活动页面";
    }

    private static boolean pageLooksHealthy(RenderedPage page) {
        String text = searchableText(page.document);
        return containsAnyToken(text, Arrays.asList("VMRack", "云服务器", "VPS", "CN2 GIA", "对象存储"));
    }

    private static boolean pageHasOfferSignals(RenderedPage page) {
        String text = searchableText(page.document);
        return hasPositivePrice(text)
                && containsAnyToken(text, Arrays.asList("VMRack", "VPS", "云服务器", "CN2 GIA", "三网精品"))
                && looksLikeConcreteServerOffer(text);
    }

    private static String searchableText(Document document) {
        StringBuilder text = new StringBuilder(document.text());
        for (Element meta : document.select("meta[content]")) {
            text.append(' ').append(meta.attr("content"));
        }
        for (Element script : document.select("script[type=application/json], script#__NUXT_DATA__")) {
            text.append(' ').append(script.data());
        }
        return normalize(text.toString());
    }

    private static boolean looksLikeServerOffer(String text) {
        return containsAnyToken(text, Arrays.asList("VPS", "云服务器", "vCPU", "CPU", "内存", "带宽"));
    }

    private static boolean looksLikeConcreteServerOffer(String text) {
        return LEVEL_SERVER_NAME_PATTERN.matcher(text).find()
                || CPU_MEMORY_PLAN_PATTERN.matcher(text).find()
                || (containsAnyToken(text, Arrays.asList("vCPU", "CPU"))
                && containsAnyToken(text, Arrays.asList("内存", "RAM", "Memory", "GB"))
                && containsAnyToken(text, Arrays.asList("VPS", "云服务器", "带宽", "流量", "Bandwidth", "Traffic")));
    }

    private static boolean hasPrice(String text) {
        return PRICE_PATTERN.matcher(text).find();
    }

    private static boolean hasPositivePrice(String text) {
        return firstPositivePrice(text) != null;
    }

    private static boolean isPremium(String text, List<String> premiumKeywords) {
        return containsAnyToken(text, premiumKeywords);
    }

    private static boolean pageSuggestsPremium(String url, boolean pagePremium) {
        return pagePremium && looksLikeDeployPage(url);
    }

    private static boolean looksLikeDeployPage(String url) {
        return url != null && url.contains("/deploy-new-instance/vps-hosting");
    }

    private static String extractName(String text) {
        Matcher levelMatcher = LEVEL_SERVER_NAME_PATTERN.matcher(text);
        if (levelMatcher.find()) {
            return levelMatcher.group().trim();
        }
        Matcher genericMatcher = GENERIC_SERVER_NAME_PATTERN.matcher(text);
        if (genericMatcher.find()) {
            return genericMatcher.group().trim();
        }
        if (text.contains("三网精品")) {
            return "VMRack 三网精品服务器";
        }
        return "VMRack 云服务器";
    }

    private static Price firstPositivePrice(String text) {
        Matcher matcher = PRICE_PATTERN.matcher(text);
        while (matcher.find()) {
            double amount = Double.parseDouble(matcher.group(1));
            if (amount <= 0.01) {
                continue;
            }
            PricePeriod period = PricePeriod.from(matcher.group(2));
            return new Price(matcher.group().replaceAll("\\s+", ""), amount, period);
        }
        return null;
    }

    private static List<String> csvToList(String value) {
        if (!StringUtils.hasText(value)) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    private static boolean containsAnyToken(String value, List<String> tokens) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String lowerValue = value.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (lowerValue.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String value, int maxLength) {
        String normalized = normalize(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
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

    private static class RenderedPage {
        private final String url;
        private final Document document;

        private RenderedPage(String url, Document document) {
            this.url = url;
            this.document = document;
        }
    }

    static class ScanResult {
        private final List<CheapServerOffer> offers;
        private final List<ActivityCandidate> activities;

        ScanResult(List<CheapServerOffer> offers, List<ActivityCandidate> activities) {
            this.offers = offers;
            this.activities = activities;
        }
    }

    static class CheapServerOffer {
        private final String name;
        private final String priceText;
        private final double priceAmount;
        private final PricePeriod pricePeriod;
        private final String status;
        private final String sourceUrl;
        private final String context;
        private final boolean qualified;

        CheapServerOffer(String name, String priceText, double priceAmount, PricePeriod pricePeriod,
                         String status, String sourceUrl, String context, boolean qualified) {
            this.name = name;
            this.priceText = priceText;
            this.priceAmount = priceAmount;
            this.pricePeriod = pricePeriod;
            this.status = status;
            this.sourceUrl = sourceUrl;
            this.context = context;
            this.qualified = qualified;
        }

        boolean hasPrice() {
            return StringUtils.hasText(priceText);
        }

        boolean isQualified() {
            return qualified;
        }

        String getName() {
            return name;
        }

        String getPriceText() {
            return priceText;
        }

        String getSourceUrl() {
            return sourceUrl;
        }

        String uniqueKey() {
            return sourceUrl + "::" + name;
        }
    }

    static class ActivityCandidate {
        private final String title;
        private final String sourceUrl;
        private final String status;
        private final String context;
        private final boolean active;

        ActivityCandidate(String title, String sourceUrl, String status, String context, boolean active) {
            this.title = title;
            this.sourceUrl = sourceUrl;
            this.status = status;
            this.context = context;
            this.active = active;
        }

        boolean isActive() {
            return active;
        }

        String uniqueKey() {
            return sourceUrl;
        }
    }

    private static class Price {
        private final String text;
        private final double amount;
        private final PricePeriod period;

        private Price(String text, double amount, PricePeriod period) {
            this.text = text;
            this.amount = amount;
            this.period = period;
        }

        private boolean isCheap(double maxMonthlyUsd, double maxYearlyUsd) {
            if (period == PricePeriod.YEAR) {
                return amount <= maxYearlyUsd;
            }
            return amount <= maxMonthlyUsd;
        }
    }

    enum PricePeriod {
        MONTH,
        YEAR;

        static PricePeriod from(String value) {
            if (value == null) {
                return MONTH;
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            if (normalized.contains("年") || normalized.contains("yr") || normalized.contains("year")) {
                return YEAR;
            }
            return MONTH;
        }
    }
}
