package com.example.glassreader;

import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class EpubTextExtractor {

    private static final String TAG = "EpubTextExtractor";

    public interface Callback {
        void onSuccess(String text);
        void onError(String message);
        void onProgress(int current, int total);
    }

    public static void extract(final File file, final Callback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ZipFile zip = new ZipFile(file);

                    // 1. Read container.xml to find the OPF path
                    String opfPath = findOpfPath(zip);
                    if (opfPath == null) {
                        postError(mainHandler, callback, "Invalid EPUB: no OPF file found");
                        zip.close();
                        return;
                    }

                    // Base directory for resolving relative paths in OPF
                    String opfDir = "";
                    int lastSlash = opfPath.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        opfDir = opfPath.substring(0, lastSlash + 1);
                    }

                    // 2. Parse OPF to get manifest and spine
                    String opfXml = readEntry(zip, opfPath);
                    if (opfXml == null) {
                        postError(mainHandler, callback, "Cannot read OPF file");
                        zip.close();
                        return;
                    }

                    List<String> spineHrefs = parseOpf(opfXml, opfDir);
                    if (spineHrefs.isEmpty()) {
                        postError(mainHandler, callback, "No content found in EPUB spine");
                        zip.close();
                        return;
                    }

                    final int total = spineHrefs.size();
                    postProgress(mainHandler, callback, 0, total);

                    // 3. Extract text from each spine item in order
                    StringBuilder fullText = new StringBuilder();
                    for (int i = 0; i < spineHrefs.size(); i++) {
                        String href = spineHrefs.get(i);
                        String xhtml = readEntry(zip, href);
                        if (xhtml != null) {
                            String text = htmlToText(xhtml);
                            if (text.length() > 0) {
                                fullText.append(text);
                                fullText.append("\n\n");
                            }
                        }

                        final int current = i + 1;
                        postProgress(mainHandler, callback, current, total);
                    }

                    zip.close();

                    final String result = fullText.toString().trim();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(result);
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "Failed to extract EPUB", e);
                    postError(mainHandler, callback, e.getMessage());
                }
            }
        }).start();
    }

    private static String findOpfPath(ZipFile zip) throws Exception {
        String containerXml = readEntry(zip, "META-INF/container.xml");
        if (containerXml == null) return null;

        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(containerXml));

        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "rootfile".equals(parser.getName())) {
                String path = parser.getAttributeValue(null, "full-path");
                if (path != null) return path;
            }
        }
        return null;
    }

    private static List<String> parseOpf(String opfXml, String opfDir) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(opfXml));

        // Collect manifest items: id -> href
        Map<String, String> manifest = new HashMap<>();
        // Collect spine order: list of idref
        List<String> spineIdrefs = new ArrayList<>();

        boolean inManifest = false;
        boolean inSpine = false;
        int event;

        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("manifest".equals(name)) {
                    inManifest = true;
                } else if ("spine".equals(name)) {
                    inSpine = true;
                } else if ("item".equals(name) && inManifest) {
                    String id = parser.getAttributeValue(null, "id");
                    String href = parser.getAttributeValue(null, "href");
                    String mediaType = parser.getAttributeValue(null, "media-type");
                    if (id != null && href != null) {
                        // Only include XHTML/HTML content
                        if (mediaType == null
                                || mediaType.contains("html")
                                || mediaType.contains("xml")) {
                            manifest.put(id, href);
                        }
                    }
                } else if ("itemref".equals(name) && inSpine) {
                    String idref = parser.getAttributeValue(null, "idref");
                    if (idref != null) {
                        spineIdrefs.add(idref);
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("manifest".equals(name)) inManifest = false;
                else if ("spine".equals(name)) inSpine = false;
            }
        }

        // Resolve spine idrefs to full zip paths
        List<String> hrefs = new ArrayList<>();
        for (String idref : spineIdrefs) {
            String href = manifest.get(idref);
            if (href != null) {
                hrefs.add(opfDir + href);
            }
        }
        return hrefs;
    }

    @SuppressWarnings("deprecation")
    private static String htmlToText(String html) {
        // Strip any XML declarations that Html.fromHtml can't handle
        html = html.replaceAll("<\\?xml[^>]*\\?>", "");
        // Remove doctype
        html = html.replaceAll("<!DOCTYPE[^>]*>", "");
        // Remove head section (CSS, metadata)
        html = html.replaceAll("(?s)<head[^>]*>.*?</head>", "");
        // Convert block elements to line breaks for proper spacing
        html = html.replaceAll("(?i)</?(div|section|article|aside|nav|header|footer)[^>]*>", "<br>");
        html = html.replaceAll("(?i)</li>", "<br></li>");
        html = html.replaceAll("(?i)</tr>", "<br></tr>");
        html = html.replaceAll("(?i)</dt>", "<br></dt>");
        html = html.replaceAll("(?i)</dd>", "<br></dd>");
        html = html.replaceAll("(?i)</blockquote>", "<br></blockquote>");
        // Html.fromHtml handles p, br, h1-h6, b, i, etc.
        String text = Html.fromHtml(html).toString().trim();
        // Collapse runs of 3+ blank lines down to 2
        text = text.replaceAll("\n{3,}", "\n\n");
        return text;
    }

    private static String readEntry(ZipFile zip, String path) {
        try {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) return null;
            InputStream is = zip.getInputStream(entry);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            is.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Failed to read ZIP entry: " + path, e);
            return null;
        }
    }

    private static void postError(Handler handler, final Callback callback, final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }

    private static void postProgress(Handler handler, final Callback callback,
                                     final int current, final int total) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                callback.onProgress(current, total);
            }
        });
    }
}
