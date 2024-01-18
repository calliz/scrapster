package org.tretton37;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class Scrapster {
    public static final String URL = "https://books.toscrape.com/";
    // Keep track of visited links
    private final Set<String> visited;

    // Avoid stackoverflow and loops
    private static final int MAX_DEPTH = 2;

    public Scrapster() {
        visited = new HashSet<>();
    }

    private void scrapePage(String url, int maxDepth) {
        if (maxDepth < MAX_DEPTH && !visited.contains(url)) {
            try {
                // Add if not already visited
                visited.add(url);

                // Fetch page
                Document doc = Jsoup.connect(url).get();

                // Get page content
                String content = doc.outerHtml();

                // Save content
                savePageContent(url, content);

                // Get all page links
                Elements links = doc.select("a[href]");

                // For each link, increase depth and fetch and save content recursively
                for (Element link : links) {
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
