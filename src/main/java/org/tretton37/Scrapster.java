package org.tretton37;

import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;

public class Scrapster {
    public static final String URL = "https://books.toscrape.com/";
    // Keep track of visited links
    private static Set<String> visited = new HashSet<>();

    // Avoid stackoverflow and loops
    private static final int MAX_DEPTH = 1;

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

        // Get html page content using HttpClient to make the scraping blocking
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .build();
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to create request for url: {} ", url, e);
            return Flux.empty();
        }

        HttpResponse<String> response;
        try {
            LOGGER.debug("Send http request to: {}", url);
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException |
                 InterruptedException e) {
            LOGGER.error("Error getting response from url: {}", url, e);
            return Flux.empty();
        }

        String pageContent = response.body();
        // LOGGER.debug("Fetched content: {}", pageContent);

        // Save content and continue
        savePageContent(pageContent, url);

        // Get all page links
        Elements pageLinks = Jsoup.parse(pageContent, url)
                                  .select("a[href]");

        // For each link, increase depth and fetch and save content recursively
        Flux<Object> resultFlux = Flux.fromIterable(pageLinks)
                                      // get raw link
                                      .map(link -> link.attr("href"))
                                      // Add base url if relative link
                                      .map(link -> link.startsWith("http://") || link.startsWith("https://") ? link : URL + link)
                                      .doOnNext(link -> LOGGER.debug("Scraping link: {}", link))
                                      .flatMap(link -> scrapePage(link, maxDepth + 1));

        return resultFlux;
    }

    private static void savePageContent(String content, String url) {
        LOGGER.debug("Saving page content for url: {}", url);

        String filename = Math.abs(url.hashCode()) + ".txt";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
            writer.write(content);
            LOGGER.debug("Saved url {} to disk as: {}", url, filename);
        } catch (IOException e) {
            LOGGER.error("Error: failed to save page content for url {} to disk.", url, e);
        }
    }

    public static void main(String[] args) {
        LOGGER.info("Starting web scraping of: {}", URL);
        new Scrapster().scrapePage(URL, 0)
                       .doOnComplete(() -> LOGGER.info("Scraping completed.\n" +
                               "Skipped due to max depth: {} urls\n" +
                               "Skipped due to already visited: {} urls\n" +
                               "Urls visited {}", skippedMaxDepth, skippedVisited, visited.size()))
                       .subscribe();
    }
}
