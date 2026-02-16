package com.example.glassreader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;

public class PdfTextExtractor {

    private static final String TAG = "PdfTextExtractor";
    private static boolean initialized = false;

    public interface Callback {
        void onSuccess(String text);
        void onError(String message);
        void onProgress(int page, int totalPages);
    }

    public static void extract(final Context context, final File file, final Callback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!initialized) {
                        PDFBoxResourceLoader.init(context.getApplicationContext());
                        initialized = true;
                    }

                    PDDocument document = PDDocument.load(file);
                    final int totalPages = document.getNumberOfPages();

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onProgress(0, totalPages);
                        }
                    });

                    StringBuilder fullText = new StringBuilder();
                    PDFTextStripper stripper = new PDFTextStripper();

                    for (int i = 1; i <= totalPages; i++) {
                        stripper.setStartPage(i);
                        stripper.setEndPage(i);
                        String pageText = stripper.getText(document);
                        fullText.append(pageText);

                        final int currentPage = i;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onProgress(currentPage, totalPages);
                            }
                        });
                    }

                    document.close();

                    final String result = fullText.toString();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(result);
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "Failed to extract text", e);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }
}
