package org.tretton37;

import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    public static final String URL = "https://books.toscrape.com/";
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

    private Flux<Object> scrapePage(String url, int maxDepth) {
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

        return fetchHtmlContent(url).flatMapMany(content -> fetchResources(url, maxDepth, content))
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

                       // Download the resource
                       return WEB_CLIENT.get()
                                        .uri(resourceUrl)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .flatMap(resource -> saveContent(resource, resourceUrl, resourceType));
                   }, CONCURRENCY_LIMIT) // Limit parallel requests to not overflow client or server
                   .thenMany(Flux.fromIterable(Jsoup.parse(content, url)
                                                    .select("a[href]")))
                   .map(link -> link.attr("abs:href"))
                   .buffer(CONCURRENCY_LIMIT) // Buffer links for better performance
                   // .doOnNext(link -> LOGGER.debug("Scraping link: {}", link))
                   // Limit parallel requests to not overflow client or server
                   .flatMap(links -> Flux.fromIterable(links)
                                         .flatMap(link -> scrapePage(link, maxDepth + 1), 10));
    }

    private static Mono<String> fetchHtmlContent(String url) {
        return WEB_CLIENT.get()
                         .uri(url)
                         .retrieve()
                         .onStatus(HttpStatus::isError, clientResponse -> Mono.error(new RuntimeException("Error fetching url: " + url)))
                         .bodyToMono(String.class)
                         .doOnSubscribe(subscription -> LOGGER.info("Starting fetch for url: {}", url))
                         .doOnSuccess(content -> {
                             // LOGGER.debug("Completed fetch for url: {}", url);
                             // LOGGER.debug("Content for url {} : \n{}", url, content);
                         })
                         .flatMap(content -> {
                             LOGGER.info("Fetched content from url: {}", url);
                             // Save html content and thenReturn to pass content from Mono<Void>
                             return saveContent(content, url, ResourceType.HTML).thenReturn(content);
                         });
    }

    private static Mono<Void> saveContent(String content, String url, ResourceType resourceType) {
        // LOGGER.debug("Saving content for url: {}", url);

        // Create folder for type
        String folder = switch (resourceType) {
            case HTML -> "html";
            case CSS -> "css";
            case JS -> "js";
            case IMAGE -> "image";
        };
        String path = folder + "/" + Math.abs(url.hashCode()) + "." + resourceType.toString()
                                                                                  .toLowerCase();

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

    public static void main(String[] args) {
        LOGGER.info("Starting web scraping of: {}", URL);
        new Scrapster().scrapePage(URL, 0)
                       .doOnComplete(() -> LOGGER.info("""
                               Scraping completed.
                               Skipped due to max depth: {} urls
                               Skipped due to already visited: {} urls
                               Urls visited {}""", skippedMaxDepth, skippedVisited, visited.size()))
                       .blockLast();
    }
}
