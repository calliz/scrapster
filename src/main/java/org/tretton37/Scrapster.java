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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;

public class Scrapster {
    public static final String URL = "https://books.toscrape.com/";
    // Keep track of visited links
    private static Set<String> visited = new HashSet<>();

    // Avoid stackoverflow and loops
    private static final int MAX_DEPTH = 2;

    private static final WebClient WEB_CLIENT = WebClient.builder()
                                                         .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                                                         .build();

    private static final Logger LOGGER = LoggerFactory.getLogger(Scrapster.class);
    private static int skippedMaxDepth = 0;
    private static int skippedVisited = 0;

    private Flux<Object> scrapePage(String url, int maxDepth) {
        LOGGER.debug("Scraping page {} using max depth {}", url, maxDepth);
        if (maxDepth >= MAX_DEPTH) {
            skippedMaxDepth++;
            LOGGER.debug("Skipped, max depth reached: {}", url);
            return Flux.empty();
        } else if (visited.contains(url)) {
            skippedVisited++;
            LOGGER.debug("Skipped, already visited: {}", url);
            return Flux.empty();
        }

        // Add if not already visited
        visited.add(url);

        // Get html page content using webclient to make the scraping non-blocking
        Mono<String> pageContent = WEB_CLIENT.get()
                                             .uri(url)
                                             .retrieve()
                                             .onStatus(HttpStatus::isError, clientResponse -> Mono.error(new RuntimeException("Error fetching url: " + url)))
                                             .bodyToMono(String.class)
                                             .doOnSubscribe(subscription -> LOGGER.info("Starting fetch for url: {}", url))
                                             .doOnSuccess(content -> {
                                                 LOGGER.debug("Completed fetch for url: {}", url);
                                                 // LOGGER.debug("Content for url {} : \n{}", url, content);
                                             })
                                             .flatMap(content -> {
                                                 LOGGER.info("Fetched content from url: {}", url);
                                                 // Save content and continue
                                                 return savePageContent(content, url).thenReturn(content);
                                             })
                                             /*.onErrorResume(e -> {
                                                 LOGGER.error("Error fetching content from url: {}", url, e);
                                                 return Mono.empty();
                                             })*/;


        return pageContent.flatMapMany(content -> {
            // Get all page links
            Elements pageLinks = Jsoup.parse(content)
                                      .select("a[href]");

            // For each link, increase depth and fetch and save content recursively
            return Flux.fromIterable(pageLinks)
                       // get raw link
                       .map(link -> link.attr("href"))
                       // Add base url if relative link
                       .map(link -> {
                           try {
                               URI uri = new URI(link);
                               if (uri.isAbsolute()) {
                                   return link;
                               } else {
                                   return URL + link;
                               }
                           } catch (URISyntaxException e) {
                               LOGGER.debug("Failed to parse link: " + link);
                               return URL + link;
                           }
                       })
                       .doOnNext(link -> LOGGER.debug("Scraping link: {}", link))
                       .flatMap(link -> scrapePage(link, maxDepth + 1));
        });
    }

    private static Mono<Void> savePageContent(String content, String url) {
        LOGGER.debug("Saving page content for url: {}", url);
        String filename = Math.abs(url.hashCode()) + ".txt";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
            writer.write(content);
            LOGGER.debug("Saved url {} to disk as: {}", url, filename);
        } catch (IOException e) {
            LOGGER.error("Error: failed to save page content for url {} to disk.", url, e);
        }
        return Mono.empty();
    }

    public static void main(String[] args) {
        LOGGER.info("Starting web scraping of: {}", URL);
        new Scrapster().scrapePage(URL, 0)
                       .doOnComplete(() -> LOGGER.info("Scraping completed.\n" +
                               "Skipped due to max depth: {} urls\n" +
                               "Skipped due to already visited: {} urls\n" +
                               "Urls visited {}", skippedMaxDepth, skippedVisited, visited.size()))
                       .blockLast();
    }
}
