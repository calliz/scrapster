package org.tretton37;

import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
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
    private static final int MAX_DEPTH = 2;

    private Flux<Object> scrapePage(String url, int maxDepth) {
        if (maxDepth >= MAX_DEPTH) {
            return Flux.empty();
        } else if (visited.contains(url)) {
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
            return Flux.empty();
        }

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException |
                 InterruptedException e) {
            return Flux.empty();
        }

        String pageContent = response.body();

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
                                      .flatMap(link -> scrapePage(link, maxDepth + 1));

        return resultFlux;
    }

    private static void savePageContent(String content, String url) {
        System.out.println(url);
        // System.out.println(content);
        System.out.println(visited.size() + " urls visited");
        String filename = Math.abs(url.hashCode()) + ".txt";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
            writer.write(content);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        new Scrapster().scrapePage(URL, 0)
                       .subscribe();
    }
}
