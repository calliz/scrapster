package org.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;

public class Scrapster {

    private void scrapePage(String url) {
        try {
            // Fetch page
            Document doc = Jsoup.connect(url).get();
            // Get page content
            String content = doc.outerHtml();

            // Save content
            savePageContent(url, content);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void savePageContent(String url, String content) throws IOException {
        System.out.println(url);
        // System.out.println(content);
    }

    public static void main(String[] args) {
        new Scrapster().scrapePage("http://example.com");
    }
}
