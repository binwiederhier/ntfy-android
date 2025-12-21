package io.heckel.ntfy.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the emojis from a JSON database.
 *
 * This was originally written to load
 * https://github.com/vdurmont/emoji-java/blob/master/src/main/resources/emojis.json
 *
 * But now uses
 * https://github.com/github/gemoji/blob/master/db/emoji.json
 *
 * This class was originally written by Vincent DURMONT (vdurmont@gmail.com) as part of
 * https://github.com/vdurmont/emoji-java, but has since been heavily stripped and modified.
 */
public class EmojiLoader {
  public static List<Emoji> loadEmojis(InputStream stream) throws IOException, JSONException {
    JSONArray emojisJSON = new JSONArray(inputStreamToString(stream));
    List<Emoji> emojis = new ArrayList<Emoji>(emojisJSON.length());
    for (int i = 0; i < emojisJSON.length(); i++) {
      Emoji emoji = buildEmojiFromJSON(emojisJSON.getJSONObject(i));
      if (emoji != null) {
        emojis.add(emoji);
      }
    }
    return emojis;
  }

  private static String inputStreamToString(
    InputStream stream
  ) throws IOException {
    StringBuilder sb = new StringBuilder();
    InputStreamReader isr = new InputStreamReader(stream, StandardCharsets.UTF_8);
    BufferedReader br = new BufferedReader(isr);
    String read;
    while((read = br.readLine()) != null) {
      sb.append(read);
    }
    br.close();
    return sb.toString();
  }

  protected static Emoji buildEmojiFromJSON(
    JSONObject json
  ) throws JSONException {
    if (!json.has("emoji")) {
      return null;
    }

    byte[] bytes = json.getString("emoji").getBytes(StandardCharsets.UTF_8);
    List<String> aliases = jsonArrayToStringList(json.getJSONArray("aliases"));
    return new Emoji(aliases, bytes);
  }

  private static List<String> jsonArrayToStringList(JSONArray array) throws JSONException {
    List<String> strings = new ArrayList<String>(array.length());
    for (int i = 0; i < array.length(); i++) {
      strings.add(array.getString(i));
    }
    return strings;
  }
}
