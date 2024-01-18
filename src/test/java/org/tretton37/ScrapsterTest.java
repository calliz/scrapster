package org.tretton37;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScrapsterTest {

    @BeforeEach
    public void setup() throws IOException {
        // Clean directories before each test
        Files.walk(Path.of("html"))
             .map(Path::toFile)
             .forEach(file -> file.delete());

        Files.walk(Path.of("css"))
             .map(Path::toFile)
             .forEach(file -> file.delete());

        Files.walk(Path.of("js"))
             .map(Path::toFile)
             .forEach(file -> file.delete());

        Files.walk(Path.of("image"))
             .map(Path::toFile)
             .forEach(file -> file.delete());
    }

    @Test
    public void testScrapingAndSaving() throws IOException {
        String url = "https://books.toscrape.com/";
        new Scrapster().scrapePage(url, url, 0).blockLast();

        // Check if scraped contents are saved in dirs
        assertTrue(Files.list(Path.of("html")).count() > 0);
        assertTrue(Files.list(Path.of("css")).count() > 0);
        assertTrue(Files.list(Path.of("js")).count() > 0);
        assertTrue(Files.list(Path.of("image")).count() > 0);
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Clean dirs after test
        Files.walk(Path.of("html"))
             .map(Path::toFile)
             .forEach(file -> file.delete());

        Files.walk(Path.of("css"))
             .map(Path::toFile)
             .forEach(file -> file.delete());

        Files.walk(Path.of("js"))
             .map(Path::toFile)
             .forEach(file -> file.delete());

        Files.walk(Path.of("image"))
             .map(Path::toFile)
             .forEach(file -> file.delete());
    }
}