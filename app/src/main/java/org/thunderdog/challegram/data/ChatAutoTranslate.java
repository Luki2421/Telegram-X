/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.data;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.unsorted.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.vkryl.core.StringUtils;

/**
 * Per-chat automatic translation: stores the target language for each chat and provides
 * a fallback translator for when TDLib's translateText request fails.
 */
@me.vkryl.annotation.MadeByLLM(value = "claude-opus-5-5", id = "chat-auto-translate")
public final class ChatAutoTranslate {
  private static final String KEY_PREFIX = "chat_auto_translate_";

  private ChatAutoTranslate () { }

  private static String key (int accountId, long chatId) {
    return KEY_PREFIX + accountId + "_" + chatId;
  }

  public static @Nullable String getLanguage (int accountId, long chatId) {
    if (chatId == 0) {
      return null;
    }
    String language = Settings.instance().pmc().getString(key(accountId, chatId), null);
    return StringUtils.isEmpty(language) ? null : language;
  }

  public static void setLanguage (int accountId, long chatId, @Nullable String language) {
    if (chatId == 0) {
      return;
    }
    if (StringUtils.isEmpty(language)) {
      Settings.instance().pmc().remove(key(accountId, chatId));
    } else {
      Settings.instance().pmc().putString(key(accountId, chatId), language);
    }
  }

  public interface TranslationCallback {
    void onTranslated (@Nullable String translatedText);
  }

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "ChatAutoTranslate");
    thread.setDaemon(true);
    return thread;
  });

  /**
   * Same as {@link #translateWithGoogle(String, String)}, but runs on a dedicated
   * network thread and delivers the result on that thread.
   */
  public static void translateWithGoogleAsync (String text, String toLanguage, TranslationCallback callback) {
    EXECUTOR.execute(() -> callback.onTranslated(translateWithGoogle(text, toLanguage)));
  }

  private static final int TIMEOUT_MS = 15_000;
  private static final int MAX_CHUNK = 4500;

  /**
   * Translates plain text with the public Google Translate endpoint. Formatting entities are
   * not preserved. Must be called off the main thread.
   *
   * @return translated text, or {@code null} on failure
   */
  public static @Nullable String translateWithGoogle (String text, String toLanguage) {
    if (StringUtils.isEmpty(text) || StringUtils.isEmpty(toLanguage)) {
      return null;
    }
    StringBuilder result = new StringBuilder(text.length());
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(text.length(), start + MAX_CHUNK);
      if (end < text.length()) {
        int newline = text.lastIndexOf('\n', end);
        if (newline > start) {
          end = newline + 1;
        }
      }
      String translated = translateChunk(text.substring(start, end), toLanguage);
      if (translated == null) {
        return null;
      }
      result.append(translated);
      start = end;
    }
    return result.toString();
  }

  private static @Nullable String translateChunk (String text, String toLanguage) {
    HttpURLConnection connection = null;
    try {
      String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" +
        URLEncoder.encode(toLanguage, "UTF-8") + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8");
      connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setConnectTimeout(TIMEOUT_MS);
      connection.setReadTimeout(TIMEOUT_MS);
      connection.setRequestProperty("User-Agent", "Mozilla/5.0");
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        return null;
      }
      String body;
      try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
          out.write(buffer, 0, read);
        }
        body = out.toString(StandardCharsets.UTF_8.name());
      }
      JSONArray sentences = new JSONArray(body).getJSONArray(0);
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < sentences.length(); i++) {
        JSONArray sentence = sentences.optJSONArray(i);
        if (sentence != null && !sentence.isNull(0)) {
          b.append(sentence.getString(0));
        }
      }
      return b.toString();
    } catch (Throwable t) {
      Log.w("Google translation failed", t);
      return null;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
