package org.tretton37;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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

    private void scrapePage(String url, int maxDepth) {
        if (maxDepth < MAX_DEPTH && !visited.contains(url)) {
            try {
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
                    return;
                }

                HttpResponse<String> response;
                try {
                    response = client.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (IOException |
                         InterruptedException e) {
                    return;
                }

                String pageContent = response.body();

                // Save content and continue
                savePageContent(url, pageContent);

                // Get all page links
                Elements pageLinks = Jsoup.parse(pageContent, url)
                                          .select("a[href]");

                // For each link, increase depth and fetch and save content recursively
                for (Element link : pageLinks) {
                    scrapePage(link.attr("abs:href"), maxDepth + 1);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void savePageContent(String url, String content) throws IOException {
        System.out.println(url);
        // System.out.println(content);
        System.out.println(visited.size() + " urls visited");
    }

    public static void main(String[] args) {
        new Scrapster().scrapePage(URL, 0);
    }
}
