package org.tretton37;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

public class Scrapster {
    public static final int CONCURRENCY_LIMIT = 7;
    // Keep track of visited links
    private static Set<String> visited = new HashSet<>();

    // Avoid stackoverflow and loops
    private static final int MAX_DEPTH = 3;

    private static final WebClient WEB_CLIENT = WebClient.builder()
                                                         .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                                                         .build();

    private static final Logger LOGGER = LoggerFactory.getLogger(Scrapster.class);
    private static int skippedMaxDepth = 0;
    private static int skippedVisited = 0;

    public enum ResourceType {
        HTML,
        CSS,
        JS,
        IMAGE
    }

    Flux<Object> scrapePage(String url, String mainUrl, int maxDepth) {
        // LOGGER.debug("Scraping page {} using max depth {}", url, maxDepth);
        if (maxDepth >= MAX_DEPTH) {
            skippedMaxDepth++;
            // LOGGER.debug("Skipped, max depth reached: {}", url);
            return Flux.empty();
        } else if (visited.contains(url)) {
            skippedVisited++;
            // LOGGER.debug("Skipped, already visited: {}", url);
            return Flux.empty();
        }

        // Add if not already visited
        visited.add(url);

        return fetchHtmlContent(url, mainUrl).flatMapMany(content -> fetchResources(url, maxDepth, content))
                                             .onErrorResume(e -> {
                                                 LOGGER.error("Failed to parse content from url: {}", url, e);
                                                 return Mono.empty();
                                             });
    }

    private Flux<Object> fetchResources(String url, int maxDepth, String content) {
        Elements pageResources = Jsoup.parse(content, url)
                                      .select("[src],[href]");

        return Flux.fromIterable(pageResources)
                   .flatMap(pageResource -> {
                       ResourceType resourceType;
                       String resourceUrl;
                       if (pageResource.tagName()
                                       .equals("img") && pageResource.hasAttr("src")) {
                           resourceType = ResourceType.IMAGE;
                           resourceUrl = pageResource.attr("abs:src");
                       } else if (pageResource.tagName()
                                              .equals("link") && pageResource.hasAttr("href") && pageResource.attr("href")
                                                                                                             .endsWith(".css")) {
                           resourceType = ResourceType.CSS;
                           resourceUrl = pageResource.attr("abs:href");
                       } else if (pageResource.tagName()
                                              .equals("script") && pageResource.hasAttr("src")) {
                           resourceType = ResourceType.JS;
                           resourceUrl = pageResource.attr("abs:src");
                       } else {
                           // Skip other content types for now
                           return Mono.empty();
                       }

                       return fetchAndSaveResources(resourceUrl, resourceType);
                   }, CONCURRENCY_LIMIT) // Limit parallel requests to not overflow client or server
                   .thenMany(Flux.fromIterable(Jsoup.parse(content, url)
                                                    .select("a[href]")))
                   .map(link -> link.attr("abs:href"))
                   .buffer(CONCURRENCY_LIMIT) // Buffer links for better performance
                   // .doOnNext(link -> LOGGER.debug("Scraping link: {}", link))
                   // Limit parallel requests to not overflow client or server
                   .flatMap(links -> Flux.fromIterable(links)
                                         .flatMap(link -> scrapePage(link, null, maxDepth + 1), 10));
    }

    private static Mono<Void> fetchAndSaveResources(String resourceUrl, ResourceType resourceType) {
        return fetchResourceContent(resourceUrl)
                .flatMap(resource -> saveContent(resource, resourceUrl, null, resourceType));
    }

    private static Mono<String> fetchResourceContent(String resourceUrl) {
        return WEB_CLIENT.get()
                         .uri(resourceUrl)
                         .retrieve()
                         .bodyToMono(String.class);
    }

    private static Mono<String> fetchHtmlContent(String url, String mainUrl) {
        return WEB_CLIENT.get()
                         .uri(url)
                         .retrieve()
                         .onStatus(HttpStatus::isError, clientResponse -> Mono.error(new RuntimeException("Error fetching url: " + url)))
                         .bodyToMono(String.class)
                         .doOnSubscribe(subscription -> LOGGER.info("Starting fetch for url: {}", url))
                         .flatMap(content -> {
                             LOGGER.info("Fetched content from url: {}", url);
                             // LOGGER.debug("Content for url {} : \n{}", url, content);
                             // Save html content and thenReturn to pass content from Mono<Void>
                             return saveContent(content, url, mainUrl, ResourceType.HTML).thenReturn(content);
                         });
    }

    private static Mono<Void> saveContent(String content, String url, String mainUrl, ResourceType resourceType) {
        // LOGGER.debug("Saving content for url: {}", url);

        // Create folder for type
        String folder = switch (resourceType) {
            case HTML -> "html";
            case CSS -> "css";
            case JS -> "js";
            case IMAGE -> "image";
        };

        String filename;
        if (resourceType == ResourceType.HTML && !StringUtils.isEmpty(mainUrl) && url.equals(mainUrl)) {
            filename = "index.html";  // Main page saved as index.htmlß
        } else {
            filename = Math.abs(url.hashCode()) + "." + resourceType.toString()
                                                                    .toLowerCase();
        }

        String path = folder + "/" + filename;
        content = convertRealLinksToOfflineLinks(content, url, resourceType);

        return saveContentToDisk(content, url, folder, path);
    }

    private static Mono<Void> saveContentToDisk(String content, String url, String folder, String path) {
        // Create dirs in separate thread to avoid blocking
        return Mono.fromCallable(() -> {
                       // Create dir if not exist
                       Path directoryPath = Paths.get(folder);
                       if (!Files.exists(directoryPath)) {
                           Files.createDirectories(directoryPath);
                       }
                       BufferedWriter writer = new BufferedWriter(new FileWriter(path));
                       writer.write(content);
                       writer.close();
                       LOGGER.debug("Saved url {} to disk as: {}", url, path);
                       return null;
                   })
                   .subscribeOn(Schedulers.boundedElastic())
                   // return Mono<Void>
                   .then()
                   .doOnError(IOException.class, e -> LOGGER.error("Error: failed to save content for url {} to path {}.", url, path, e));
    }

    private static String convertRealLinksToOfflineLinks(String content, String url, ResourceType resourceType) {
        if (resourceType == ResourceType.HTML) {
            Document document = Jsoup.parse(content, url);

            // HTML links
            Elements linkElements = document.select("a[href]");
            linkElements.forEach(link -> {
                String absoluteUrl = link.attr("abs:href");
                link.attr("href", "html/" + Math.abs(absoluteUrl.hashCode()) + ".html");
            });

            // CSS links
            Elements cssLinkElements = document.select("link[href$=.css]");
            cssLinkElements.forEach(cssLink -> {
                String absoluteUrl = cssLink.attr("abs:href");
                cssLink.attr("href", "css/" + Math.abs(absoluteUrl.hashCode()) + ".css");
            });

            // JS links
            Elements scriptElements = document.select("script[src]");
            scriptElements.forEach(script -> {
                String absoluteUrl = script.attr("abs:src");
                script.attr("src", "js/" + Math.abs(absoluteUrl.hashCode()) + ".js");
            });

            // Image links
            Elements imgElements = document.select("img[src]");
            imgElements.forEach(img -> {
                String absoluteUrl = img.attr("abs:src");
                img.attr("src", "image/" + Math.abs(absoluteUrl.hashCode()) + ".image");
            });

            // Overwrite with updated links
            content = document.html();
        }
        return content;
    }

    public static void main(String[] args) {
        // URL from args
        String url;
        if (args.length > 0) {
            url = args[0];
        } else {
            System.out.println("Please provide a URL as argument. Using default URL.");
            url = "https://books.toscrape.com/";
        }
        LOGGER.info("Starting web scraping of: {}", url);
        new Scrapster().scrapePage(url, url, 0)
                       .doOnComplete(() -> LOGGER.info("""
                               Scraping completed.
                               Skipped due to max depth: {} urls
                               Skipped due to already visited: {} urls
                               Urls visited {}""", skippedMaxDepth, skippedVisited, visited.size()))
                       .blockLast();
    }
}
