package com.example.glassreader;

import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

public class TextPaginator {

    private final List<String> wrappedLines = new ArrayList<>();
    private final List<List<String>> pages = new ArrayList<>();
    private int linesPerPage;

    public void paginate(String text, Paint paint, int maxWidth, int usableHeight, float lineHeight) {
        wrappedLines.clear();
        pages.clear();

        String[] paragraphs = text.split("\n");
        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) {
                wrappedLines.add("");
            } else {
                wrapParagraph(paragraph.trim(), paint, maxWidth);
            }
        }

        linesPerPage = (int) (usableHeight / lineHeight);
        if (linesPerPage <= 0) linesPerPage = 1;

        for (int i = 0; i < wrappedLines.size(); i += linesPerPage) {
            int end = Math.min(i + linesPerPage, wrappedLines.size());
            pages.add(new ArrayList<>(wrappedLines.subList(i, end)));
        }

        if (pages.isEmpty()) {
            List<String> emptyPage = new ArrayList<>();
            emptyPage.add("");
            pages.add(emptyPage);
        }
    }

    private void wrapParagraph(String text, Paint paint, int maxWidth) {
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();

        for (String word : words) {
            if (line.length() == 0) {
                line.append(word);
                // Break overly long words
                breakLongWord(line, paint, maxWidth);
            } else {
                String test = line.toString() + " " + word;
                if (paint.measureText(test) <= maxWidth) {
                    line.append(" ").append(word);
                } else {
                    wrappedLines.add(line.toString());
                    line = new StringBuilder(word);
                    breakLongWord(line, paint, maxWidth);
                }
            }
        }
        if (line.length() > 0) {
            wrappedLines.add(line.toString());
        }
    }

    private void breakLongWord(StringBuilder line, Paint paint, int maxWidth) {
        while (paint.measureText(line.toString()) > maxWidth && line.length() > 1) {
            int breakAt = 1;
            while (breakAt < line.length()
                    && paint.measureText(line.substring(0, breakAt + 1)) <= maxWidth) {
                breakAt++;
            }
            wrappedLines.add(line.substring(0, breakAt));
            String remainder = line.substring(breakAt);
            line.setLength(0);
            line.append(remainder);
        }
    }

    public List<String> getWrappedLines() {
        return wrappedLines;
    }

    public List<List<String>> getPages() {
        return pages;
    }

    public int getPageCount() {
        return pages.size();
    }

    public int getLinesPerPage() {
        return linesPerPage;
    }

    public int getTotalLines() {
        return wrappedLines.size();
    }

    public List<String> getPage(int index) {
        if (index >= 0 && index < pages.size()) {
            return pages.get(index);
        }
        return new ArrayList<>();
    }
}
